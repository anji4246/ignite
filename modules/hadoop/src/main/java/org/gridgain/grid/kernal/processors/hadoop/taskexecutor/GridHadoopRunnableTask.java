/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.taskexecutor;

import org.gridgain.grid.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.shuffle.collections.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.offheap.unsafe.*;
import org.gridgain.grid.util.typedef.internal.*;

import java.util.*;

import static org.gridgain.grid.hadoop.GridHadoopJobProperty.*;
import static org.gridgain.grid.hadoop.GridHadoopTaskType.*;

/**
 * Runnable task.
 */
public abstract class GridHadoopRunnableTask implements GridPlainCallable<Void> {
    /** */
    private final GridUnsafeMemory mem;

    /** */
    private GridLogger log;

    /** */
    private final GridHadoopJob job;

    /** Task to run. */
    private final GridHadoopTaskInfo info;

    /** Submit time. */
    private long submitTs = System.currentTimeMillis();

    /** Execution start timestamp. */
    private long execStartTs;

    /** Execution end timestamp. */
    private long execEndTs;

    /** */
    private GridHadoopMultimap local;

    /** */
    private volatile GridHadoopTask task;

    /** Set if task is to cancelling. */
    private volatile boolean cancelled;

    /**
     * @param log Log.
     * @param job Job.
     * @param mem Memory.
     * @param info Task info.
     */
    public GridHadoopRunnableTask(GridLogger log, GridHadoopJob job, GridUnsafeMemory mem, GridHadoopTaskInfo info) {
        this.log = log;
        this.job = job;
        this.mem = mem;
        this.info = info;
    }

    /**
     * @return Wait time.
     */
    public long waitTime() {
        return execStartTs - submitTs;
    }

    /**
     * @return Execution time.
     */
    public long executionTime() {
        return execEndTs - execStartTs;
    }

    /** {@inheritDoc} */
    @Override public Void call() throws GridException {
        execStartTs = System.currentTimeMillis();

        boolean runCombiner = info.type() == MAP && job.info().hasCombiner() &&
            !get(job.info(), SINGLE_COMBINER_FOR_ALL_MAPPERS, false);

        GridHadoopTaskState state = GridHadoopTaskState.COMPLETED;
        Throwable err = null;

        try {
            job.prepareTaskEnvironment(info);

            runTask(info, runCombiner);

            if (runCombiner)
                runTask(new GridHadoopTaskInfo(info.nodeId(), COMBINE, info.jobId(), info.taskNumber(), info.attempt(),
                    null), runCombiner);
        }
        catch (GridHadoopTaskCancelledException ignored) {
            state = GridHadoopTaskState.CANCELED;
        }
        catch (Throwable e) {
            state = GridHadoopTaskState.FAILED;
            err = e;

            U.error(log, "Task execution failed.", e);
        }
        finally {
            try {
                job.cleanupTaskEnvironment(info);
            }
            catch (Throwable e) {
                if (err == null)
                    err = e;

                U.error(log, "Error on cleanup task environment.", e);
            }

            execEndTs = System.currentTimeMillis();

            onTaskFinished(state, err);

            if (runCombiner)
                local.close();
        }

        return null;
    }

    /**
     * @param info Task info.
     * @param locCombiner If we have mapper with combiner.
     * @throws GridException If failed.
     */
    private void runTask(GridHadoopTaskInfo info, boolean locCombiner) throws GridException {
        if (cancelled)
            throw new GridHadoopTaskCancelledException("Task cancelled.");

        GridHadoopTaskContext ctx = job.getTaskContext(info);

        try (GridHadoopTaskOutput out = createOutput(ctx, locCombiner);
             GridHadoopTaskInput in = createInput(ctx, locCombiner)) {

            ctx.input(in);
            ctx.output(out);

            task = job.createTask(info);

            if (cancelled)
                throw new GridHadoopTaskCancelledException("Task cancelled.");

            task.run(ctx, log);
        }
    }

    /**
     * Cancel the executed task.
     */
    public void cancel() {
        cancelled = true;

        if (task != null)
            task.cancel();
    }

    /**
     * @param state State.
     * @param err Error.
     */
    protected abstract void onTaskFinished(GridHadoopTaskState state, Throwable err);

    /**
     * @param ctx Task context.
     * @param locCombiner If we have mapper with combiner.
     * @return Task input.
     * @throws GridException If failed.
     */
    @SuppressWarnings("unchecked")
    private GridHadoopTaskInput createInput(GridHadoopTaskContext ctx, boolean locCombiner) throws GridException {
        switch (ctx.taskInfo().type()) {
            case SETUP:
            case MAP:
            case COMMIT:
            case ABORT:
                return null;

            case COMBINE:
                if (locCombiner) {
                    assert local != null;

                    return local.input(ctx, (Comparator<Object>) ctx.combineGroupComparator());
                }

            default:
                return createInput(ctx);
        }
    }

    /**
     * @param ctx Task context.
     * @return Input.
     * @throws GridException If failed.
     */
    protected abstract GridHadoopTaskInput createInput(GridHadoopTaskContext ctx) throws GridException;

    /**
     * @param ctx Task info.
     * @return Output.
     * @throws GridException If failed.
     */
    protected abstract GridHadoopTaskOutput createOutput(GridHadoopTaskContext ctx) throws GridException;

    /**
     * @param ctx Task info.
     * @param locCombiner If we have mapper with combiner.
     * @return Task output.
     * @throws GridException If failed.
     */
    private GridHadoopTaskOutput createOutput(GridHadoopTaskContext ctx, boolean locCombiner) throws GridException {
        switch (ctx.taskInfo().type()) {
            case SETUP:
            case REDUCE:
            case COMMIT:
            case ABORT:
                return null;

            case MAP:
                if (locCombiner) {
                    assert local == null;

                    local = get(job.info(), SHUFFLE_COMBINER_NO_SORTING, false) ?
                        new GridHadoopHashMultimap(job, mem, get(job.info(), COMBINER_HASHMAP_SIZE, 8 * 1024)):
                        new GridHadoopSkipList(job, mem, ctx.sortComparator()); // TODO replace with red-black tree

                    return local.startAdding(ctx);
                }

            default:
                return createOutput(ctx);
        }
    }

    /**
     * @return Task info.
     */
    public GridHadoopTaskInfo taskInfo() {
        return info;
    }
}
