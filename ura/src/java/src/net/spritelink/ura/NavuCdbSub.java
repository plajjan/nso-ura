package net.spritelink.ura;

import java.util.Arrays;

import org.apache.log4j.Logger;

import com.tailf.cdb.*;
import com.tailf.conf.*;
import com.tailf.maapi.*;
import com.tailf.navu.*;
import com.tailf.ncs.ApplicationComponent;
import com.tailf.ncs.annotations.*;
import com.tailf.ncs.ns.Ncs;

import java.net.InetAddress;
import java.util.*;

// This Navu cdb subscriber subscribes to changes under the path
// /t:test/config-item
// Whenever a change occurs there, the code iterates through the
// change and prints the values. Thus to trigger this subscription code
// go into the ncs_cli and commit any change under the subscription
//     path. For example:

// # ncs_cli -u admin
// admin connected from 127.0.0.1 using console on iron.local
// admin@iron> configure
// dmin@iron% set test config-item k23 i 99
// [ok][2012-07-05 12:57:59]

// [edit]
// admin@iron% commit
// Commit complete.

// will trigger the subscription code, the code logs and the data will end up
// in ./logs/ncs-java-vm.log (relative to where the ncs daemon executes)

// The code runs in an 'application' component, it implements
// the ApplicationComponent interface, this includes the run() method
// so the code will run in a Thread.

public class NavuCdbSub implements ApplicationComponent {
    private static Logger LOGGER = Logger.getLogger(NavuCdbSub.class);

    @Resource(type=ResourceType.CDB, scope=Scope.CONTEXT,
            qualifier="reactive-fm-loop-subscriber")
    private Cdb cdb;

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE,
            qualifier="reactive-fm-m")
    private Maapi maapi;

    private int th = -1;
    private NavuContainer ncsRoot;
    private NavuContainer operRoot;

    private NavuCdbConfigSubscriber sub;

    public NavuCdbSub() {
    }

    private enum Operation { ALLOCATE, DEALLOCATE }
    private enum Type { Integer }

    private class Request {
        Operation op;
        Type t;
        ConfPath path;
        ConfKey pool_key;
        ConfKey request_key;
        NavuContainer confPool;
        NavuContainer confPoolReq;
        NavuContainer operPool;
        NavuContainer operPoolReq;
    }

    public void init() {
        try {
            maapi.startUserSession("admin", InetAddress.getLocalHost(),"system",
                    new String[] {"admin"},
                    MaapiUserSessionFlag.PROTO_TCP);
            th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
            ncsRoot = new NavuContainer(new NavuContext(maapi, th)).container(Ncs.hash);
            operRoot = new NavuContainer(new NavuContext(cdb)).container(Ncs.hash);

            sub = (NavuCdbConfigSubscriber) NavuCdbSubscribers.
                configSubscriber("localhost", Conf.NCS_PORT);

            sub.register(new NavuCdbConfigDiffIterate() {
                private ArrayList<Request> reqs = new ArrayList<Request>();
                private final Logger SUBLOG =
                    Logger.getLogger(NavuCdbSub.class);

                public void iterate(NavuCdbSubscriptionConfigContext ctx) {
                    Request r = new Request();
                    NavuNode parent = null;
                    NavuNode current = ctx.getNode();

                    switch (ctx.getOperFlag()) {
                        case MOP_DELETED:
                            break;
                        case MOP_CREATED:
                            break;
                        case MOP_MODIFIED:
                            break;
                    }
                    ctx.iterRecurse();
                }
            }, new ConfPath("/ncs:services/ura:ura"));

            sub.registerSyncTypeOnException(
                CdbSubscriptionSyncType.DONE_SOCKET);
        } catch (Exception e) {
            throw new RuntimeException("Fail in init", e);
        }
    }

    public void run() {
        int counter  = 0;
        NavuCdbSubscriptionIterQueue queue = null;

        sub.subscriberStart();
        queue = sub.getIterationQueue();
        LOGGER.info("subscribed: ready");

        try {
            IterationEntry entry = null;
            ArrayList<Request> reqs = new ArrayList<Request>();

            while (!Thread.currentThread().interrupted() ) {
                entry = queue.nextIteration();
                ConfPath path = entry.keyPath();
                DiffIterateOperFlag op = entry.getOperFlag();

                Request r = new Request();
                r.path = path;

                ConfObject[] kp = path.getKP();
                if (kp[1].toString().equals("ura:request")) {
                    r.request_key = (ConfKey)kp[0];
                    if (kp[3].toString().equals("ura:integer")) {
                        r.t = Type.Integer;
                        r.pool_key = (ConfKey)kp[2];
                    }
                    if (op == DiffIterateOperFlag.MOP_CREATED) {
                        r.op = Operation.ALLOCATE;
                        reqs.add(r);
                    } else if (op == DiffIterateOperFlag.MOP_DELETED) {
                        r.op = Operation.DEALLOCATE;
                        reqs.add(r);
                    }
                }

                // deal with queued requests
                if (reqs.size() > 0) {
                    LOGGER.info("Number of requests: " + reqs.size());
                }
                for (Request req : reqs) {
                    LOGGER.info("Requested URA action " + req.request_key +
                            ", op=" + req.op + " , type=" + req.t +
                            " , from=" + req.pool_key);

                    req.confPool = ncsRoot.container("ncs", "services").
                        container("ura", "ura").
                        list("ura", "integer").
                        elem(req.pool_key);
                    req.confPoolReq = req.confPool.list("ura", "request").elem(req.request_key);

                    req.operPool = operRoot.container("ncs", "services").
                        container("ura", "ura").
                        list("ura", "integer").
                        elem(req.pool_key);
                    req.operPoolReq = req.operPool.list("ura", "request").elem(req.request_key);

                    if ((req.op == Operation.ALLOCATE) && (req.t == Type.Integer)) {
                        try {
                            allocateInteger(req);
                        } catch (Exception e) {
                            LOGGER.info("Something went to shitez: ", e);
                        }
                    }
                    if ((req.op == Operation.DEALLOCATE) && (req.t == Type.Integer)) {
                        try {
                            deallocateInteger(req);
                        } catch (Exception e) {
                            LOGGER.info("Something went to shitez: ", e);
                        }
                    }
                }
                reqs.clear();
            }
        } catch (ConfException e) {
            LOGGER.info("shit");
        } catch (InterruptedException e) {
            LOGGER.info("Got Interrupted!");
            Thread.currentThread().interrupt();
        } finally {
            List<IterationEntry> remaining = queue.drainRemaining () ;
            for (IterationEntry  entry : remaining) {
                LOGGER.info("(" + (counter++) + ") " + entry.getOperFlag() +
                        " " + entry.keyPath() + " " + entry.getLastResultFlag() );
            }
        }
    }

    /*
     * Allocate an integer!
     */
    public void allocateInteger (Request req) throws NavuException {
        // get pool settings
        ConfEnumeration allocMethod = (ConfEnumeration)req.confPool.leaf("allocation-method").value();
        long minVal = Long.parseLong(req.confPool.leaf("min-value").valueAsString());
        long maxVal = Long.parseLong(req.confPool.leaf("max-value").valueAsString());
        LOGGER.info("Pool: " + req.confPool.leaf("name").valueAsString() + " min: " + minVal + " max: " + maxVal + " allocMethod: " + allocMethod.getOrdinalValue());

        List<Long> numbers = new ArrayList<Long>();
        // fetch all the current values from CDB
        // for large number of existing values, this will be rather slow and memory efficient
        // for the "max" method we could perhaps do a xpath max() to fetch the max value instead
        for (NavuContainer oreq: req.operPool.list("request")) {
            try {
                numbers.add(Long.parseLong(oreq.leaf("integer").valueAsString()));
            } catch (Exception e) {
            }
        }

        long newVal = minVal;
        if (allocMethod.getOrdinalValue() == 0) {
            // this just takes the highest number and adds by one
            // it will fail 
            if (numbers.size() > 0) {
                newVal = Collections.max(numbers) + 1;
            }
        } else if (allocMethod.getOrdinalValue() == 1) {
            Collections.sort(numbers);

            // find next free number
            newVal = numbers.size();
            for(int i=0; i < numbers.size(); i++) {
                if(numbers.get(i) != (long)i) {
                    newVal = i;
                    break;
                }
            }
        }

        // Write the result
        ConfUInt64 integerValue = new ConfUInt64(newVal);
        LOGGER.info("SET: " + req.path + "/integer -> " + integerValue);
        req.operPoolReq.leaf("integer").set(integerValue);

        ConfValue redeployPath = req.confPoolReq.leaf("redeploy-service").value();
        if (redeployPath != null) {
            redeploy(redeployPath + "/re-deploy");
        }

    }

    /*
     * Deallocate an integer!
     */
    public void deallocateInteger (Request req) throws NavuException {
        req.operPoolReq.leaf("integer").delete();
    }


    public void finish() {
        try {
            sub.subscriberStop();
            sub.executor().shutdown();
        } catch (Exception ignore) {
        }
    }


    // redeploy MUST be done in another thread, if not system
    // hangs, since the CDB subscriber cannot do its work
    private void redeploy(String path) {
        Redeployer r = new Redeployer(maapi, path);
        Thread t = new Thread(r);
        t.start();
    }

    private class Redeployer implements Runnable {
        private String path;
        private Maapi m;

        public Redeployer(Maapi m, String path) {
            this.path = path; this.m = m;
        }

        public void run() {
            try {
                m.requestAction(new ConfXMLParam[] {},
                        path);
            } catch (Exception e) {
                LOGGER.error("error in re-deploy", e);
                throw new RuntimeException("error in re-deploy", e);
            }
        }
    }
}
