/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.v2;

import org.apache.hadoop.conf.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.*;
import org.gridgain.grid.hadoop.*;
import org.jetbrains.annotations.*;

import java.io.*;

/**
 * Split serialized in external file.
 */
public class GridHadoopExternalSplit extends GridHadoopInputSplit {
    /** */
    private static final long serialVersionUID = 0L;

    /** */
    private long off;

    /**
     * For {@link Externalizable}.
     */
    public GridHadoopExternalSplit() {
        // No-op.
    }

    /**
     * @param hosts Hosts.
     * @param off Offset of this split in external file.
     */
    public GridHadoopExternalSplit(String[] hosts, long off) {
        assert off >= 0 : off;
        assert hosts != null;

        this.hosts = hosts;
        this.off = off;
    }

    /**
     * @return Offset of this input split in external file.
     */
    public long offset() {
        return off;
    }

    /** {@inheritDoc} */
    @Override public void writeExternal(ObjectOutput out) throws IOException {
        out.writeLong(off);
    }

    /** {@inheritDoc} */
    @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        off = in.readLong();
    }

    /** {@inheritDoc} */
    @Override public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o == null || getClass() != o.getClass())
            return false;

        GridHadoopExternalSplit that = (GridHadoopExternalSplit) o;

        return off == that.off;
    }

    /** {@inheritDoc} */
    @Override public int hashCode() {
        return (int)(off ^ (off >>> 32));
    }

    /**
     * Reads class name from input stream and tries get one from configuration.
     *
     * @param in Input stream.
     * @param off Offset in stream.
     * @param jobConf Job configuration.
     * @return Class or {@code null} if not found.
     * @throws IOException If failed.
     */
    @Nullable public static Class<?> readSplitClass(FSDataInputStream in, long off, Configuration jobConf)
            throws IOException {
        in.seek(off);

        String clsName = Text.readString(in);

        try {
            return jobConf.getClassByName(clsName);
        }
        catch (ClassNotFoundException e) {
            throw new IOException(e);
        }
    }
}
