/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkalex.source.pgsql;

import java.io.Serializable;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.logging.Level;
import org.redkale.net.*;
import org.redkale.service.Local;
import org.redkale.source.*;
import org.redkale.util.*;

/**
 * 部分协议格式参考： http://wp1i.cn/archives/78556.html
 *
 * @author zhangjx
 */
@Local
@AutoLoad(false)
@SuppressWarnings("unchecked")
@ResourceType(DataSource.class)
public class PgsqlDataSource extends DataSqlSource {

    public PgsqlDataSource(String unitName, URL persistxml, Properties readprop, Properties writeprop) {
        super(unitName, persistxml, readprop, writeprop);
    }

    @Override
    public void init(AnyValue conf) {
        super.init(conf);
    }

    @Local
    protected PgPoolSource readPoolSource() {
        return (PgPoolSource) readPool;
    }

    @Local
    protected PgPoolSource writePoolSource() {
        return (PgPoolSource) writePool;
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
    protected PoolSource createPoolSource(DataSource source, AsyncGroup asyncGroup, String rwtype, ArrayBlockingQueue queue, Semaphore semaphore, Properties prop) {
        return new PgPoolSource(asyncGroup, rwtype, queue, semaphore, prop, logger);
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
        return executeUpdate(info, sql, values, 0, true, attrs, objs);
    }

    @Override
    protected <T> CompletableFuture<Integer> deleteDB(EntityInfo<T> info, Flipper flipper, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            final String debugsql = flipper == null || flipper.getLimit() <= 0 ? sql : (sql + " LIMIT " + flipper.getLimit());
            if (info.isLoggable(logger, Level.FINEST, debugsql)) logger.finest(info.getType().getSimpleName() + " delete sql=" + debugsql);
        }
        return executeUpdate(info, sql, null, fetchSize(flipper), false, null);
    }

    @Override
    protected <T> CompletableFuture<Integer> clearTableDB(EntityInfo<T> info, final String table, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " clearTable sql=" + sql);
        }
        return executeUpdate(info, sql, null, 0, false, null);
    }

    @Override
    protected <T> CompletableFuture<Integer> dropTableDB(EntityInfo<T> info, final String table, String sql) {
        if (info.isLoggable(logger, Level.FINEST)) {
            if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " dropTable sql=" + sql);
        }
        return executeUpdate(info, sql, null, 0, false, null);
    }

    @Override
    protected <T> CompletableFuture<Integer> updateDB(EntityInfo<T> info, ChannelContext context, final T... values) {
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
        PgPoolSource pool = writePoolSource();
        if (pool.client.prepareCacheable) {
            String sql = info.getUpdateDollarPrepareSQL(values[0]);
            PgReqExtendedCommand req = new PgReqExtendedCommand(PgClientRequest.REQ_TYPE_EXTEND_UPDATE, pool.client.extendedStatementIndex(sql), info, sql, 0, Utility.append(attrs, primary), objs);
            return pool.sendAsync(context, req).thenApply(g -> g.getUpdateEffectCount());
        } else {
            return executeUpdate(info, info.getUpdateDollarPrepareSQL(values[0]), null, 0, false, Utility.append(attrs, primary), objs);
        }
    }

    @Override
    protected <T> CompletableFuture<Integer> updateDB(EntityInfo<T> info, Flipper flipper, String sql, boolean prepared, Object... params) {
        if (info.isLoggable(logger, Level.FINEST)) {
            final String debugsql = flipper == null || flipper.getLimit() <= 0 ? sql : (sql + " LIMIT " + flipper.getLimit());
            if (info.isLoggable(logger, Level.FINEST, debugsql)) logger.finest(info.getType().getSimpleName() + " update sql=" + debugsql);
        }
        Object[][] objs = params == null || params.length == 0 ? null : new Object[][]{params};
        return executeUpdate(info, sql, null, fetchSize(flipper), false, null, objs);
    }

    @Override
    protected <T, N extends Number> CompletableFuture<Map<String, N>> getNumberMapDB(EntityInfo<T> info, String sql, FilterFuncColumn... columns) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
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
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return map;
        });
    }

    @Override
    protected <T> CompletableFuture<Number> getNumberResultDB(EntityInfo<T> info, String sql, Number defVal, String column) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
            Number rs = defVal;
            try {
                if (set.next()) {
                    Object o = set.getObject(1);
                    if (o != null) rs = (Number) o;
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        });
    }

    @Override
    protected <T, K extends Serializable, N extends Number> CompletableFuture<Map<K, N>> queryColumnMapDB(EntityInfo<T> info, String sql, String keyColumn) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
            Map<K, N> rs = new LinkedHashMap<>();
            try {
                while (set.next()) {
                    rs.put((K) set.getObject(1), (N) set.getObject(2));
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        });
    }

    @Override
    protected <T, K extends Serializable, N extends Number> CompletableFuture<Map<K[], N[]>> queryColumnMapDB(EntityInfo<T> info, String sql, final ColumnNode[] funcNodes, final String[] groupByColumns) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
            Map rs = new LinkedHashMap<>();
            try {
                while (set.next()) {
                    int index = 0;
                    Serializable[] keys = new Serializable[groupByColumns.length];
                    for (int i = 0; i < keys.length; i++) {
                        keys[i] = (Serializable) set.getObject(++index);
                    }
                    Number[] vals = new Number[funcNodes.length];
                    for (int i = 0; i < vals.length; i++) {
                        vals[i] = (Number) set.getObject(++index);
                    }
                    rs.put(keys, vals);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return rs;
        });
    }

    @Override
    protected <T> CompletableFuture<T> findCompose(final EntityInfo<T> info, ChannelContext context, final SelectColumn selects, Serializable pk) {
        if (info.getTableStrategy() == null && selects == null && readPoolSource().client.prepareCacheable) {
            PgPoolSource pool = readPoolSource();
            String sql = info.getFindDollarPrepareSQL(null);
            PgReqExtendedCommand req = new PgReqExtendedCommand(PgClientRequest.REQ_TYPE_EXTEND_QUERY, pool.client.extendedStatementIndex(sql), info, sql, 0, null, new Object[]{pk});
            return pool.sendAsync(context, req).thenApply((PgResultSet set) -> {
                PgResultSet pgset = set.realResult == null ? set : set.copy();
                T rs = (T) pgset.realResult;
                if (rs == null) {
                    try {
                        rs = set.next() ? getEntityValue(info, selects, set) : null;
                        pgset.realResult = rs;
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                return rs;
            });
        }
        String column = info.getPrimarySQLColumn();
        final String sql = "SELECT " + info.getQueryColumns(null, selects) + " FROM " + info.getTable(pk) + " WHERE " + column + "=" + info.formatSQLValue(column, pk, sqlFormatter);
        if (info.isLoggable(logger, Level.FINEST, sql)) logger.finest(info.getType().getSimpleName() + " find sql=" + sql);
        return findDB(info, context, sql, true, selects);
    }

    @Override
    protected <T> CompletableFuture<T> findDB(EntityInfo<T> info, ChannelContext context, String sql, boolean onlypk, SelectColumn selects) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
            PgResultSet pgset = (PgResultSet) set;
            T rs = (T) pgset.realResult;
            if (rs == null) {
                try {
                    rs = set.next() ? getEntityValue(info, selects, set) : null;
                    pgset.realResult = rs;
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return rs;
        });
    }

    @Override
    protected <T> CompletableFuture<Serializable> findColumnDB(EntityInfo<T> info, String sql, boolean onlypk, String column, Serializable defValue) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
            Serializable val = defValue;
            try {
                if (set.next()) {
                    final Attribute<T, Serializable> attr = info.getAttribute(column);
                    val = getFieldValue(info, attr, set, 1);
                }
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return val == null ? defValue : val;
        });
    }

    @Override
    protected <T> CompletableFuture<Boolean> existsDB(EntityInfo<T> info, String sql, boolean onlypk) {
        return executeQuery(info, sql).thenApply((ResultSet set) -> {
            try {
                boolean rs = set.next() ? (set.getInt(1) > 0) : false;
                return rs;
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    protected <T> CompletableFuture<Sheet<T>> querySheetDB(EntityInfo<T> info, final boolean readcache, boolean needtotal, final boolean distinct, SelectColumn selects, Flipper flipper, FilterNode node) {
        final SelectColumn sels = selects;
        final Map<Class, String> joinTabalis = node == null ? null : getJoinTabalis(node);
        final CharSequence join = node == null ? null : createSQLJoin(node, this, false, joinTabalis, new HashSet<>(), info);
        final CharSequence where = node == null ? null : createSQLExpress(node, info, joinTabalis);
        final PgPoolSource pool = readPoolSource();
        final boolean queryallcacheflag = pool.client.prepareCacheable && readcache && info.getTableStrategy() == null && sels == null && node == null && flipper == null && !distinct;
        final String listsql = queryallcacheflag ? info.getAllQueryPrepareSQL() : ("SELECT " + (distinct ? "DISTINCT " : "") + info.getQueryColumns("a", selects) + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join)
            + ((where == null || where.length() == 0) ? "" : (" WHERE " + where)) + createSQLOrderby(info, flipper) + (flipper == null || flipper.getLimit() < 1 ? "" : (" LIMIT " + flipper.getLimit() + " OFFSET " + flipper.getOffset())));
        if (readcache && info.isLoggable(logger, Level.FINEST, listsql)) logger.finest(info.getType().getSimpleName() + " query sql=" + listsql);
        if (!needtotal) {
            CompletableFuture<PgResultSet> listfuture;
            if (queryallcacheflag) {
                PgReqExtendedCommand req = new PgReqExtendedCommand(PgClientRequest.REQ_TYPE_EXTEND_QUERY, pool.client.extendedStatementIndex(listsql), info, listsql, 0, null);
                listfuture = pool.sendAsync(null, req);
            } else {
                listfuture = executeQuery(info, listsql);
            }
            return listfuture.thenApply((ResultSet set) -> {
                if (queryallcacheflag) {
                    List oldarray = (List) info.getSubobject("alllist");
                    if (oldarray != null) return Sheet.asSheet(new ArrayList<>(oldarray));
                }
                PgResultSet pgset = (PgResultSet) set;
                final List<T> list = new ArrayList();
                Object[] rs = (Object[]) pgset.realResult;
                try {
                    if (rs == null) {
                        while (set.next()) {
                            list.add(getEntityValue(info, sels, set));
                        }
                        if (readPoolSource().client.prepareCacheable) pgset.realResult = list.toArray();
                    } else {
                        for (Object t : rs) {
                            list.add((T) t);
                        }
                    }
                    Sheet sheet = Sheet.asSheet(list);
                    if (queryallcacheflag) info.setSubobject("alllist", new ArrayList<>(list));
                    return sheet;
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        final String countsql = "SELECT " + (distinct ? "DISTINCT COUNT(" + info.getQueryColumns("a", selects) + ")" : "COUNT(*)") + " FROM " + info.getTable(node) + " a" + (join == null ? "" : join)
            + ((where == null || where.length() == 0) ? "" : (" WHERE " + where));
        return getNumberResultDB(info, countsql, 0, countsql).thenCompose(total -> {
            if (total.longValue() <= 0) return CompletableFuture.completedFuture(new Sheet<>(0, new ArrayList()));
            return executeQuery(info, listsql).thenApply((ResultSet set) -> {
                try {
                    final List<T> list = new ArrayList();
                    while (set.next()) {
                        list.add(getEntityValue(info, sels, set));
                    }
                    return new Sheet(total.longValue(), list);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    private static int fetchSize(Flipper flipper) {
        return flipper == null || flipper.getLimit() <= 0 ? 0 : flipper.getLimit();
    }

    protected <T> CompletableFuture<Integer> executeUpdate(final EntityInfo<T> info, final String sql, final T[] values, int fetchSize, final boolean insert, final Attribute<T, Serializable>[] attrs, final Object[]... parameters) {
        PgClientRequest req = insert ? new PgReqInsert(info, sql, fetchSize, attrs, parameters) : new PgReqUpdate(info, sql, fetchSize, attrs, parameters);
        return writePoolSource().sendAsync(null, req).thenApply(g -> g.getUpdateEffectCount());
    }

    //info可以为null,供directQuery
    protected <T> CompletableFuture<PgResultSet> executeQuery(final EntityInfo<T> info, final String sql) {
        return readPoolSource().sendAsync(null, new PgReqQuery(info, sql));
    }

    @Local
    @Override
    public int directExecute(String sql) {
        return executeUpdate(null, sql, null, 0, false, null).join();
    }

    @Local
    @Override
    public int[] directExecute(String... sqls) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Local
    @Override
    public <V> V directQuery(String sql, Function<ResultSet, V> handler) {
        return executeQuery(null, sql).thenApply((ResultSet set) -> {
            return handler.apply(set);
        }).join();
    }

}
