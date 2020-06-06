/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.redkalex.cluster.consul;

import org.redkale.cluster.ClusterAgent;
import java.lang.reflect.Type;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.*;
import javax.annotation.Resource;
import org.redkale.boot.*;
import static org.redkale.boot.Application.RESNAME_APP_ADDR;
import org.redkale.convert.json.JsonConvert;
import org.redkale.service.Service;
import org.redkale.util.*;

/**
 * <blockquote><pre>
 *  &lt;cluster value="org.redkalex.cluster.consul.ConsulClusterAgent"&gt;
 *      &lt;property name="apiurl" value="http://localhost:8500/v1"/&gt;
 *      &lt;property name="ttls" value="10"/&gt;
 *  &lt;/cluster&gt;
 * </pre></blockquote>
 *
 * @author zhangjx
 */
public class ConsulClusterAgent extends ClusterAgent {

    protected static final Map<String, String> httpHeaders = Utility.ofMap("Content-Type", "application/json", "Accept", "application/json");

    protected static final Type MAP_STRING_ADDRESSENTRY = new TypeToken<Map<String, AddressEntry>>() {
    }.getType();

    @Resource(name = RESNAME_APP_ADDR)
    protected String localAddress;

    protected String apiurl;

    protected int ttls = 10; //定时检查的秒数

    protected ScheduledThreadPoolExecutor scheduler;

    @Override
    public void init(AnyValue config) {
        super.init(config);
        AnyValue[] properties = config.getAnyValues("property");
        for (AnyValue property : properties) {
            if ("apiurl".equalsIgnoreCase(property.getValue("name"))) {
                this.apiurl = property.getValue("value", "").trim();
                if (this.apiurl.endsWith("/")) this.apiurl = this.apiurl.substring(0, this.apiurl.length() - 1);
            } else if ("ttls".equalsIgnoreCase(property.getValue("name"))) {
                this.ttls = Integer.parseInt(property.getValue("value", "").trim());
                if (this.ttls < 5) this.ttls = 10;
            }
        }
    }

    @Override
    public void destroy(AnyValue config) {
        if (scheduler != null) scheduler.shutdownNow();
    }

    @Override //ServiceLoader时判断配置是否符合当前实现类
    public boolean match(AnyValue config) {
        if (config == null) return false;
        AnyValue[] properties = config.getAnyValues("property");
        if (properties == null || properties.length == 0) return false;
        for (AnyValue property : properties) {
            if ("apiurl".equalsIgnoreCase(property.getValue("name"))) return true;
        }
        return false;
    }

    @Override
    public void start() {
        if (this.scheduler == null) {
            this.scheduler = new ScheduledThreadPoolExecutor(3, (Runnable r) -> {
                final Thread t = new Thread(r, ConsulClusterAgent.class.getSimpleName() + "-Task-Thread");
                t.setDaemon(true);
                return t;
            });
            AtomicInteger offset = new AtomicInteger();
            for (final ClusterEntry entry : localEntrys.values()) {
                entry.checkScheduledFuture = this.scheduler.scheduleAtFixedRate(() -> {
                    checkLocalHealth(entry);
                }, offset.incrementAndGet() * 100, ttls * 1000 * 4 / 5, TimeUnit.MILLISECONDS);
            }
            for (final ClusterEntry entry : remoteEntrys.values()) {
                if (!"SNCP".equalsIgnoreCase(entry.protocol)) continue;
                entry.checkScheduledFuture = this.scheduler.scheduleAtFixedRate(() -> {
                    updateSncpTransport(entry);
                }, offset.incrementAndGet() * 88, ttls * 1000, TimeUnit.MILLISECONDS);  //88错开delay
            }
        }
    }

    protected void checkLocalHealth(final ClusterEntry entry) {
        try {
            String rs = Utility.remoteHttpContent("PUT", this.apiurl + "/agent/check/pass/" + entry.checkid, httpHeaders, (String) null).toString(StandardCharsets.UTF_8);
            if (!rs.isEmpty()) logger.log(Level.SEVERE, entry.checkid + " check error: " + rs);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, entry.checkid + " check error", ex);
        }
    }

    @Override
    protected Collection<InetSocketAddress> queryAddress(final ClusterEntry entry) {
        final String servicename = entry.servicename;
        final HashSet<InetSocketAddress> set = new HashSet<>();
        String rs = null;
        try {
            rs = Utility.remoteHttpContent("GET", this.apiurl + "/agent/services?filter=" + URLEncoder.encode("Service==\"" + servicename + "\"", StandardCharsets.UTF_8), httpHeaders, (String) null).toString(StandardCharsets.UTF_8);
            Map<String, AddressEntry> map = JsonConvert.root().convertFrom(MAP_STRING_ADDRESSENTRY, rs);
            map.forEach((serviceid, en) -> {
                try {
                    String irs = Utility.remoteHttpContent("GET", this.apiurl + "/agent/health/service/id/" + serviceid + "?format=text", httpHeaders, (String) null).toString(StandardCharsets.UTF_8);
                    if ("passing".equalsIgnoreCase(irs)) {
                        set.add(en.createSocketAddress());
                    } else {
                        logger.log(Level.WARNING, serviceid + " health result: " + irs);
                    }
                } catch (Exception e) {
                    logger.log(Level.SEVERE, serviceid + " health format=text error", e);
                }
            });
        } catch (Exception ex) {
            logger.log(Level.SEVERE, servicename + " queryAddress error, result=" + rs, ex);
        }
        return set;
    }

    @Override
    protected void register(NodeServer ns, String protocol, Service service) {
        deregister(ns, protocol, service);
        //
        String serviceid = generateServiceId(ns, protocol, service);
        String servicename = generateServiceName(ns, protocol, service);
        InetSocketAddress address = ns.isSNCP() ? ns.getSncpAddress() : ns.getServer().getSocketAddress();
        String host = address.getHostString();
        if ("0.0.0.0".equals(host)) host = localAddress;
        String json = "{\"ID\": \"" + serviceid + "\",\"Name\": \"" + servicename + "\",\"Address\": \"" + host + "\",\"Port\": " + address.getPort()
            + ",\"Check\":{\"CheckID\": \"" + generateCheckId(ns, protocol, service) + "\",\"Name\": \"" + generateCheckName(ns, protocol, service) + "\",\"TTL\":\"" + ttls + "s\",\"Notes\":\"Interval " + ttls + "s Check\"}}";
        try {
            String rs = Utility.remoteHttpContent("PUT", this.apiurl + "/agent/service/register", httpHeaders, json).toString(StandardCharsets.UTF_8);
            if (!rs.isEmpty()) logger.log(Level.SEVERE, serviceid + " register error: " + rs);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, serviceid + " register error", ex);
        }
    }

    @Override
    protected void deregister(NodeServer ns, String protocol, Service service) {
        String serviceid = generateServiceId(ns, protocol, service);
        ClusterEntry currEntry = null;
        for (final ClusterEntry entry : localEntrys.values()) {
            if (entry.serviceid.equals(serviceid)) {
                currEntry = entry;
                break;
            }
        }
        if (currEntry == null) {
            for (final ClusterEntry entry : remoteEntrys.values()) {
                if (entry.serviceid.equals(serviceid)) {
                    currEntry = entry;
                    break;
                }
            }
        }
        try {
            String rs = Utility.remoteHttpContent("PUT", this.apiurl + "/agent/service/deregister/" + serviceid, httpHeaders, (String) null).toString(StandardCharsets.UTF_8);
            if (currEntry != null && currEntry.checkScheduledFuture != null) currEntry.checkScheduledFuture.cancel(true);
            if (!rs.isEmpty()) logger.log(Level.SEVERE, serviceid + " deregister error: " + rs);
        } catch (Exception ex) {
            logger.log(Level.SEVERE, serviceid + " deregister error", ex);
        }
    }

    public static class AddressEntry {

        public String Address;

        public int Port;

        public InetSocketAddress createSocketAddress() {
            return new InetSocketAddress(Address, Port);
        }
    }
}