package com.facebook.presto.operator;

import com.facebook.presto.operator.Page;
import com.facebook.presto.tuple.TupleInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.facebook.presto.operator.NewOperator.NOT_BLOCKED;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class InMemoryExchange
{
    private final List<TupleInfo> tupleInfos;
    private final Queue<Page> buffer;
    private final List<SettableFuture<?>> blockedCallers = new ArrayList<>();
    private boolean finishing;
    private boolean noMoreSinkFactories;
    private int sinkFactories;
    private int sinks;

    public InMemoryExchange(List<TupleInfo> tupleInfos)
    {
        this.tupleInfos = ImmutableList.copyOf(checkNotNull(tupleInfos, "tupleInfos is null"));
        this.buffer = new ConcurrentLinkedQueue<>();
    }

    public List<TupleInfo> getTupleInfos()
    {
        return tupleInfos;
    }

    public synchronized NewOperatorFactory createSinkFactory(int operatorId)
    {
        sinkFactories++;
        return new InMemoryExchangeSinkOperatorFactory(operatorId);
    }

    private synchronized void addSink()
    {
        checkState(sinkFactories > 0, "All sink factories already closed");
        sinks++;
    }

    public synchronized void sinkFinished()
    {
        checkState(sinks != 0, "All sinks are already complete");
        sinks--;
        updateState();
    }

    public synchronized void noMoreSinkFactories()
    {
        this.noMoreSinkFactories = true;
        updateState();
    }

    private synchronized void sinkFactoryClosed()
    {
        checkState(sinkFactories != 0, "All sinks factories are already closed");
        sinkFactories--;
        updateState();
    }

    private void updateState()
    {
        if (noMoreSinkFactories && sinkFactories == 0 && sinks == 0) {
            finishing = true;
        }
    }

    public synchronized boolean isFinishing()
    {
        return finishing;
    }

    public synchronized void finish()
    {
        finishing = true;
        notifyBlockedCallers();
    }

    public synchronized boolean isFinished()
    {
        return finishing && buffer.isEmpty();
    }

    public synchronized void addPage(Page page)
    {
        if (finishing) {
            return;
        }
        buffer.add(page);
        notifyBlockedCallers();
    }

    private synchronized void notifyBlockedCallers()
    {
        for (SettableFuture<?> blockedCaller : blockedCallers) {
            blockedCaller.set(null);
        }
        blockedCallers.clear();
    }

    public synchronized ListenableFuture<?> waitForNotEmpty()
    {
        if (finishing || !buffer.isEmpty()) {
            return NOT_BLOCKED;
        }
        SettableFuture<?> settableFuture = SettableFuture.create();
        blockedCallers.add(settableFuture);
        return settableFuture;
    }

    public synchronized Page removePage()
    {
        return buffer.poll();
    }

    private class InMemoryExchangeSinkOperatorFactory
            implements NewOperatorFactory
    {
        private final int operatorId;

        private InMemoryExchangeSinkOperatorFactory(int operatorId)
        {
            this.operatorId = operatorId;
        }

        @Override
        public List<TupleInfo> getTupleInfos()
        {
            return tupleInfos;
        }

        @Override
        public NewOperator createOperator(DriverContext driverContext)
        {
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, InMemoryExchangeSinkOperator.class.getSimpleName());
            addSink();
            return new InMemoryExchangeSinkOperator(operatorContext, InMemoryExchange.this);
        }

        @Override
        public void close()
        {
            sinkFactoryClosed();
        }
    }
}
