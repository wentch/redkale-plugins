/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkalex.source.mysql;

import java.io.Serializable;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import java.util.logging.*;
import org.redkale.net.AsyncConnection;
import org.redkale.service.Local;
import org.redkale.source.*;
import org.redkale.util.*;
import static org.redkalex.source.mysql.MyPoolSource.CONN_ATTR_BYTESBAME;

/**
 * 尚未实现
 *
 * @author zhangjx
 */
@Local
@AutoLoad(false)
@SuppressWarnings("unchecked")
@ResourceType(DataSource.class)
public class MySQLDataSource extends DataSqlSource<AsyncConnection> {

    public static void main(String[] args) throws Throwable {
        final Logger logger = Logger.getLogger(MySQLDataSource.class.getSimpleName());
        final int capacity = 16 * 1024;
        final ObjectPool<ByteBuffer> bufferPool = new ObjectPool<>(new AtomicLong(), new AtomicLong(), 16,
            (Object... params) -> ByteBuffer.allocateDirect(capacity), null, (e) -> {
                if (e == null || e.isReadOnly() || e.capacity() != capacity) return false;
                e.clear();
                return true;
            });

        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4, (Runnable r) -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        Properties prop = new Properties();
        prop.setProperty(DataSources.JDBC_URL, "jdbc:mysql://localhost:3306/platf_core?characterEncoding=utf8");
        prop.setProperty(DataSources.JDBC_USER, "root");
        prop.setProperty(DataSources.JDBC_PWD, "");
        MySQLDataSource source = new MySQLDataSource("", null, prop, prop);
        source.getReadPoolSource().poll();
        source.directExecute("SET NAMES utf8");
        source.directExecute("UPDATE almsrecord SET createtime = 0");
    }

    public MySQLDataSource(String unitName, URL persistxml, Properties readprop, Properties writeprop) {
        super(unitName, persistxml, readprop, writeprop);
    }

    @Local
    protected PoolSource<AsyncConnection> readPoolSource() {
        return readPool;
    }

    @Local
    protected PoolSource<AsyncConnection> writePoolSource() {
        return writePool;
    }

    protected static String readUTF8String(ByteBuffer buffer, byte[] store) {
        int i = 0;
        ByteArray array = null;
        for (byte c = buffer.get(); c != 0; c = buffer.get()) {
            if (array != null) {
                array.write(c);
            } else {
                store[i++] = c;
                if (i == store.length) {
                    array = new ByteArray(1024);
                    array.write(store);
                }
            }
        }
        return array == null ? new String(store, 0, i, StandardCharsets.UTF_8) : array.toString(StandardCharsets.UTF_8);
    }

    protected static String readUTF8String(ByteBufferReader buffer, byte[] store) {
        int i = 0;
        ByteArray array = null;
        for (byte c = buffer.get(); c != 0; c = buffer.get()) {
            if (array != null) {
                array.write(c);
            } else {
                store[i++] = c;
                if (i == store.length) {
                    array = new ByteArray(1024);
                    array.write(store);
                }
            }
        }
        return array == null ? new String(store, 0, i, StandardCharsets.UTF_8) : array.toString(StandardCharsets.UTF_8);
    }

    protected static ByteBuffer writeUTF8String(ByteBuffer buffer, String string) {
        buffer.put(string.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
        return buffer;
    }

    protected static ByteBufferWriter writeUTF8String(ByteBufferWriter buffer, String string) {
        buffer.put(string.getBytes(StandardCharsets.UTF_8));
        buffer.put((byte) 0);
        return buffer;
    }

    @Override
    protected String prepareParamSign(int index) {
        return "$" + index;
    }

    @Override
    protected final boolean isAsync() {
        return true;
    }

    @Override
    protected PoolSource<AsyncConnection> createPoolSource(DataSource source, String rwtype, ArrayBlockingQueue queue, Semaphore semaphore, Properties prop) {
        return new MyPoolSource(rwtype, queue, semaphore, prop, logger, bufferPool, executor);
    }

    @Override
    protected <T> CompletableFuture<Integer> insertDB(EntityInfo<T> info, T... values) {
        final Attribute<T, Serializable>[] attrs = info.getInsertAttributes();
        final Object[][] objs = new Object[values.length][];
        for (int i = 0; i < values.length; i++) {
            final Object[] params = new Object[attrs.length];
            for (int j = 0; j < attrs.length; j++) {
                params[j] = attrs[j].get(values[i]);
            }
            objs[i] = params;
        }
        String sql0 = info.getInsertDollarPrepareSQL(values[0]);
        //if (info.isAutoGenerated()) sql0 += " RETURNING " + info.getPrimarySQLColumn();
        final String sql = sql0;
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, values, 0, true, objs));
    }

    @Override
    protected <T> CompletableFuture<Integer> deleteDB(EntityInfo<T> info, Flipper flipper, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            final String debugsql = flipper == null || flipper.getLimit() <= 0 ? sql : (sql + " LIMIT " + flipper.getLimit());
            if (info.isLoggable(logger, Level.FINEST, debugsql)) logger.finest(info.getType().getSimpleName() + " delete sql=" + debugsql);
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, fetchSize(flipper), false));
    }

    @Override
    protected <T> CompletableFuture<Integer> clearTableDB(EntityInfo<T> info, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " clearTable sql=" + sql);
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, 0, false));
    }

    @Override
    protected <T> CompletableFuture<Integer> dropTableDB(EntityInfo<T> info, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " dropTable sql=" + sql);
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, 0, false));
    }

    @Override
    protected <T> CompletableFuture<Integer> updateDB(EntityInfo<T> info, final T... values) {
        final Attribute<T, Serializable> primary = info.getPrimary();
        final Attribute<T, Serializable>[] attrs = info.getUpdateAttributes();
        final Object[][] objs = new Object[values.length][];
        for (int i = 0; i < values.length; i++) {
            final Object[] params = new Object[attrs.length + 1];
            for (int j = 0; j < attrs.length; j++) {
                params[j] = attrs[j].get(values[i]);
            }
            params[attrs.length] = primary.get(values[i]); //最后一个是主键
            objs[i] = params;
        }
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, info.getUpdateDollarPrepareSQL(values[0]), null, 0, false, objs));
    }

    @Override
    protected <T> CompletableFuture<Integer> updateDB(EntityInfo<T> info, Flipper flipper, String sql, boolean prepared, Object... params) {
        if (info.isLoggable(logger, Level.FINEST)) {
            final String debugsql = flipper == null || flipper.getLimit() <= 0 ? sql : (sql + " LIMIT " + flipper.getLimit());
            if (info.isLoggable(logger, Level.FINEST, debugsql)) logger.finest(info.getType().getSimpleName() + " update sql=" + debugsql);
        }
        Object[][] objs = params == null || params.length == 0 ? null : new Object[][]{params};
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(info, conn, sql, null, fetchSize(flipper), false, objs));
    }

    @Override
    protected <T, N extends Number> CompletableFuture<Map<String, N>> getNumberMapDB(EntityInfo<T> info, String sql, FilterFuncColumn... columns) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            final Map map = new HashMap<>();
            try {
                if (set.next()) {
                    int index = 0;
                    for (FilterFuncColumn ffc : columns) {
                        for (String col : ffc.cols()) {
                            Object o = set.getObject(++index);
                            Number rs = ffc.getDefvalue();
                            if (o != null) rs = (Number) o;
                            map.put(ffc.col(col), rs);
                        }
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return map;
        }));
    }

    @Override
    protected <T> CompletableFuture<Number> getNumberResultDB(EntityInfo<T> info, String sql, Number defVal, String column) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            Number rs = defVal;
            try {
                if (set.next()) {
                    Object o = set.getObject(1);
                    if (o != null) rs = (Number) o;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        }));
    }

    @Override
    protected <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapDB(EntityInfo<T> info, String sql, String keyColumn) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            Map<K, N> rs = new LinkedHashMap<>();
            try {
                while (set.next()) {
                    rs.put((K) set.getObject(1), (N) set.getObject(2));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        }));
    }

    @Override
    protected <T> CompletableFuture<T> findDB(EntityInfo<T> info, String sql, boolean onlypk, SelectColumn selects) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            T rs = null;
            try {
                rs = set.next() ? getEntityValue(info, selects, set) : null;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        }));
    }

    @Override
    protected <T> CompletableFuture<Serializable> findColumnDB(EntityInfo<T> info, String sql, boolean onlypk, String column, Serializable defValue) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            Serializable val = defValue;
            try {
                if (set.next()) {
                    final Attribute<T, Serializable> attr = info.getAttribute(column);
                    val = getFieldValue(info, attr, set, 1);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return val == null ? defValue : val;
        }));
    }

    @Override
    protected <T> CompletableFuture<Boolean> existsDB(EntityInfo<T> info, String sql, boolean onlypk) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, sql).thenApply((ResultSet set) -> {
            try {
                boolean rs = set.next() ? (set.getInt(1) > 0) : false;
                return rs;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }));
    }

    @Override
    protected <T> CompletableFuture<Sheet<T>> querySheetDB(EntityInfo<T> info, final boolean readcache, boolean needtotal, SelectColumn selects, Flipper flipper, FilterNode node) {
        final SelectColumn sels = selects;
        final Map<Class, String> joinTabalis = node == null ? null : getJoinTabalis(node);
        final CharSequence join = node == null ? null : createSQLJoin(node, this, false, joinTabalis, new HashSet<>(), info);
        final CharSequence where = node == null ? null : createSQLExpress(node, info, joinTabalis);
        final String listsql = "SELECT " + info.getQueryColumns("a", selects) + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join)
            + ((where == null || where.length() == 0) ? "" : (" WHERE " + where)) + createSQLOrderby(info, flipper) + (flipper == null || flipper.getLimit() < 1 ? "" : (" LIMIT " + flipper.getLimit() + " OFFSET " + flipper.getOffset()));
        if (readcache && info.isLoggable(logger, Level.FINEST, listsql)) logger.finest(info.getType().getSimpleName() + " query sql=" + listsql);
        if (!needtotal) {
            return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, listsql).thenApply((ResultSet set) -> {
                try {
                    final List<T> list = new ArrayList();
                    while (set.next()) {
                        list.add(getEntityValue(info, sels, set));
                    }
                    return Sheet.asSheet(list);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        final String countsql = "SELECT COUNT(*) FROM " + info.getTable(node) + " a" + (join == null ? "" : join)
            + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        return getNumberResultDB(info, countsql, 0, countsql).thenCompose(total -> {
            if (total.longValue() <= 0) return CompletableFuture.completedFuture(new Sheet<>(0, new ArrayList()));
            return readPool.pollAsync().thenCompose((conn) -> executeQuery(info, conn, listsql).thenApply((ResultSet set) -> {
                try {
                    final List<T> list = new ArrayList();
                    while (set.next()) {
                        list.add(getEntityValue(info, sels, set));
                    }
                    return new Sheet(total.longValue(), list);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        });
    }

    protected static int fetchSize(Flipper flipper) {
        return flipper == null || flipper.getLimit() <= 0 ? 0 : flipper.getLimit();
    }

    protected static byte[] formatPrepareParam(Object param) {
        if (param == null) return null;
        if (param instanceof byte[]) return (byte[]) param;
        return String.valueOf(param).getBytes(UTF_8);
    }

    protected <T> CompletableFuture<Integer> executeUpdate(final EntityInfo<T> info, final AsyncConnection conn, final String sql, final T[] values, int fetchSize, final boolean insert, final Object[]... parameters) {
        final byte[] bytes = conn.getAttribute(CONN_ATTR_BYTESBAME);
        final ByteBufferWriter writer = ByteBufferWriter.create(bufferPool);
        {
            new MySQLQueryPacket(sql).writeTo(writer);
        }

        final ByteBuffer[] buffers = writer.toBuffers();
        final CompletableFuture<Integer> future = new CompletableFuture();
        conn.write(buffers, buffers, new CompletionHandler<Integer, ByteBuffer[]>() {
            @Override
            public void completed(Integer result, ByteBuffer[] attachment1) {
                if (result < 0) {
                    failed(new SQLException("Write Buffer Error"), attachment1);
                    return;
                }
                int index = -1;
                for (int i = 0; i < attachment1.length; i++) {
                    if (attachment1[i].hasRemaining()) {
                        index = i;
                        break;
                    }
                    bufferPool.accept(attachment1[i]);
                }
                if (index == 0) {
                    conn.write(attachment1, attachment1, this);
                    return;
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[attachment1.length - index];
                    System.arraycopy(attachment1, index, newattachs, 0, newattachs.length);
                    conn.write(newattachs, newattachs, this);
                    return;
                }

                final List<ByteBuffer> readBuffs = new ArrayList<>();
                conn.read(new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment2) {
                        if (result < 0) {
                            failed(new SQLException("Read Buffer Error"), attachment2);
                            return;
                        }
                        if (result == 16 * 1024 || !attachment2.hasRemaining()) { //mysqlsql数据包上限为16*1024 还有数据
                            attachment2.flip();
                            readBuffs.add(attachment2);
                            conn.read(this);
                            return;
                        }
                        attachment2.flip();
                        readBuffs.add(attachment2);
                        final ByteBufferReader buffer = ByteBufferReader.create(readBuffs);

                        boolean endok = false;
                        boolean futureover = false;
                        boolean success = false;
                        SQLException ex = null;
                        long rscount = -1;
                        MySQLOKorErrorPacket okPacket = readBuffs.size() == 1 ? new MySQLOKorErrorPacket(attachment2, bytes) : new MySQLOKorErrorPacket(buffer, bytes);
                        System.out.println("执行sql="+sql+", 结果： " + okPacket);
                        if (!okPacket.isSuccess()) {
                            ex = new SQLException(okPacket.toMessageString("MySQLOKPacket statusCode not success"), okPacket.sqlState, okPacket.vendorCode);
                        } else {
                            success = true;
                            endok = true;
                            futureover = true;
                            rscount = okPacket.affectedRows;
                        }

                        if (success) future.complete((int) rscount);
                        for (ByteBuffer buf : readBuffs) {
                            bufferPool.accept(buf);
                        }
                        if (!futureover) future.completeExceptionally(ex == null ? ex : new SQLException("SQL(" + sql + ") executeUpdate error"));
                        if (endok) {
                            writePool.offerConnection(conn);
                        } else {
                            conn.dispose();
                        }
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment2) {
                        conn.offerBuffer(attachment2);
                        future.completeExceptionally(exc);
                        conn.dispose();
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment1) {
                for (ByteBuffer attach : attachment1) {
                    bufferPool.accept(attach);
                }
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    //info可以为null,供directQuery
    protected <T> CompletableFuture<ResultSet> executeQuery(final EntityInfo<T> info, final AsyncConnection conn, final String sql) {
        final byte[] bytes = conn.getAttribute(CONN_ATTR_BYTESBAME);
        final ByteBufferWriter writer = ByteBufferWriter.create(bufferPool);
        {
            writer.put((byte) 'Q');
            int start = writer.position();
            writer.putInt(0);
            writeUTF8String(writer, sql);
            writer.putInt(start, writer.position() - start);
        }
        final ByteBuffer[] buffers = writer.toBuffers();
        final CompletableFuture<ResultSet> future = new CompletableFuture();
        conn.write(buffers, buffers, new CompletionHandler<Integer, ByteBuffer[]>() {
            @Override
            public void completed(Integer result, ByteBuffer[] attachment1) {
                if (result < 0) {
                    failed(new SQLException("Write Buffer Error"), attachment1);
                    return;
                }
                int index = -1;
                for (int i = 0; i < attachment1.length; i++) {
                    if (attachment1[i].hasRemaining()) {
                        index = i;
                        break;
                    }
                    bufferPool.accept(attachment1[i]);
                }
                if (index == 0) {
                    conn.write(attachment1, attachment1, this);
                    return;
                } else if (index > 0) {
                    ByteBuffer[] newattachs = new ByteBuffer[attachment1.length - index];
                    System.arraycopy(attachment1, index, newattachs, 0, newattachs.length);
                    conn.write(newattachs, newattachs, this);
                    return;
                }
                final MyResultSet resultSet = new MyResultSet();
                final List<ByteBuffer> readBuffs = new ArrayList<>();
                conn.read(new CompletionHandler<Integer, ByteBuffer>() {
                    @Override
                    public void completed(Integer result, ByteBuffer attachment2) {
                        if (result < 0) {
                            failed(new SQLException("Read Buffer Error"), attachment2);
                            return;
                        }
                        if (result == 8192 || !attachment2.hasRemaining()) { //postgresql数据包上限为8192 还有数据
                            attachment2.flip();
                            readBuffs.add(attachment2);
                            conn.read(this);
                            return;
                        }
                        attachment2.flip();
                        readBuffs.add(attachment2);
                        final ByteBufferReader buffer = ByteBufferReader.create(readBuffs);
                        boolean endok = false;
                        boolean futureover = false;
                        while (buffer.hasRemaining()) {
                            final char cmd = (char) buffer.get();
                            int length = buffer.getInt();
                            switch (cmd) {
                                case 'E':
                                    byte[] field = new byte[255];
                                    String level = null,
                                     code = null,
                                     message = null;
                                    for (byte type = buffer.get(); type != 0; type = buffer.get()) {
                                        String value = readUTF8String(buffer, field);
                                        if (type == (byte) 'S') {
                                            level = value;
                                        } else if (type == 'C') {
                                            code = value;
                                        } else if (type == 'M') {
                                            message = value;
                                        }
                                    }
                                    future.completeExceptionally(new SQLException(message, code, 0));
                                    futureover = true;
                                    break;
                                case 'T':
                                    //RowDesc rowDesc = new RespRowDescDecoder().read(buffer, length, bytes);
                                    //resultSet.setRowDesc(rowDesc);
                                    break;
                                case 'D':
                                    //RowData rowData = new RespRowDataDecoder().read(buffer, length, bytes);
                                    //resultSet.addRowData(rowData);
                                    futureover = true;
                                    break;
                                case 'Z':
                                    //buffer.position(buffer.position() + length - 4);
                                    buffer.skip(length - 4);
                                    endok = true;
                                    break;
                                default:
                                    //buffer.position(buffer.position() + length - 4);
                                    buffer.skip(length - 4);
                                    break;
                            }
                        }
                        for (ByteBuffer buf : readBuffs) {
                            bufferPool.accept(buf);
                        }
                        if (!futureover) future.completeExceptionally(new SQLException("SQL(" + sql + ") executeQuery error"));
                        if (endok) {
                            readPool.offerConnection(conn);
                            future.complete(resultSet);
                        } else {
                            conn.dispose();
                        }
                    }

                    @Override
                    public void failed(Throwable exc, ByteBuffer attachment2) {
                        //不用bufferPool.accept
                        future.completeExceptionally(exc);
                        conn.dispose();
                    }
                });
            }

            @Override
            public void failed(Throwable exc, ByteBuffer[] attachment1) {
                for (ByteBuffer attach : attachment1) {
                    bufferPool.accept(attach);
                }
                future.completeExceptionally(exc);
            }
        });
        return future;
    }

    @Local
    @Override
    public int directExecute(String sql) {
        return writePool.pollAsync().thenCompose((conn) -> executeUpdate(null, conn, sql, null, 0, false)).join();
    }

    @Local
    @Override
    public int[] directExecute(String... sqls) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Local
    @Override
    public <V> V directQuery(String sql, Function<ResultSet, V> handler) {
        return readPool.pollAsync().thenCompose((conn) -> executeQuery(null, conn, sql).thenApply((ResultSet set) -> {
            return handler.apply(set);
        })).join();
    }

}
