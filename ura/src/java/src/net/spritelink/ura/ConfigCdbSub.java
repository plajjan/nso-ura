package net.spritelink.ura;

import java.util.*;
import java.math.BigInteger;

import org.apache.log4j.Logger;

import net.spritelink.ura.namespaces.*;
import com.tailf.conf.*;
import com.tailf.ncs.ApplicationComponent;
import com.tailf.cdb.*;
import com.tailf.maapi.*;
import com.tailf.ncs.annotations.*;
import com.tailf.navu.*;
import com.tailf.ncs.ns.Ncs;

import java.net.SocketException;
import java.util.EnumSet;
import java.net.InetAddress;
import java.util.ArrayList;



public class ConfigCdbSub implements ApplicationComponent {
    private static Logger LOGGER = Logger.getLogger(ConfigCdbSub.class);

    private CdbSubscription sub = null;
    private CdbSession wsess;
    private CdbSession rsess;

    public ConfigCdbSub() {
    }

    @Resource(type=ResourceType.CDB, scope=Scope.CONTEXT,
            qualifier="reactive-fm-loop-subscriber")
    private Cdb cdb;

    @Resource(type=ResourceType.CDB, scope=Scope.CONTEXT,
            qualifier="w-reactive-fm-loop")
    private Cdb wcdb;

    @Resource(type=ResourceType.MAAPI, scope=Scope.INSTANCE,
            qualifier="reactive-fm-m")
    private Maapi maapi;

	private int th = -1;
	private NavuContainer ncsRoot;
	private NavuContainer operRoot;

    public void init() {
        LOGGER.info("Starting the CDB Connection...");
        try {
            wsess = wcdb.startSession(CdbDBType.CDB_OPERATIONAL);
            //Start CDB session
            maapi.startUserSession("admin", InetAddress.getLocalHost(),"system",
                    new String[] {"admin"},
                    MaapiUserSessionFlag.PROTO_TCP);

			th = maapi.startTrans(Conf.DB_RUNNING, Conf.MODE_READ);
			NavuContainer root = new NavuContainer(new NavuContext(maapi, th));
			ncsRoot = root.container(Ncs.hash);
			NavuContainer cdbRoot = new NavuContainer(new NavuContext(cdb));
			NavuContainer operRoot = cdbRoot.container(Ncs.hash);

            sub = cdb.newSubscription();
            int subid = sub.subscribe(1, new ura(), "/services/ura");
            // Tell CDB we are ready for notifications
            sub.subscribeDone();

            // Setup the external allocator
            //externalAllocator.initialize();
        }
        catch (Exception e) {
            LOGGER.error("", e);
        }
    }

    public void run() {
        LOGGER.info("Starting the CDB subscriber...");        
        try {

            while(true) {
                // Read the subscription socket for new events
                int[] points = null;
                try {
                    // Blocking call, will throw an exception on package reload/redeploy
                    points = sub.read();
                } catch (ConfException e) {
                    LOGGER.debug("Possible redeploy/reload of package, exiting");
                    return;
                }
                // DiffIterateFlags tell our DiffIterator implementation what values we want
                EnumSet<DiffIterateFlags> enumSet =
                        EnumSet.<DiffIterateFlags>of(
                                DiffIterateFlags.ITER_WANT_PREV,
                                DiffIterateFlags.ITER_WANT_ANCESTOR_DELETE,
                                DiffIterateFlags.ITER_WANT_SCHEMA_ORDER);
                ArrayList<Request> reqs = new ArrayList<Request>();
                try {
                    // Iterate through the diff tree using the Iter class
                    // reqs ArrayList is filled with requests for operations (create, delete)
                    sub.diffIterate(points[0],
                            new Iter(sub),
                            enumSet, reqs);
                }
                catch (Exception e) {
                    reqs = null;
                }

                // Loop through CREATE or DELETE requests
                for (Request req : reqs) {
                    LOGGER.info("Requested URA action, op=" + req.op + " , type=" + req.t); 

					// allocate integer
                    if ((req.op == Operation.ALLOCATE) &&
                            (req.t == Type.Integer)) {

						LOGGER.info("Trying to allocate an integer for: " + req.request_key);
						ConfEnumeration allocMethod = (ConfEnumeration)maapi.getElem(th, "/ncs:services/ura:ura/integer" + req.pool_key + "/allocation-method");
						// FUU Java - No unsigned longs and how come I can't cast to Bi
						// FUUU MAAPI, there's got to be a better way than casting via string
						long minVal = Long.parseLong(String.valueOf(maapi.getElem(th, "/ncs:services/ura:ura/integer" + req.pool_key + "/min-value")));
						long maxVal = Long.parseLong(String.valueOf(maapi.getElem(th, "/ncs:services/ura:ura/integer" + req.pool_key + "/max-value")));

						LOGGER.info("allocation-method: " + allocMethod.getOrdinalValue());
						LOGGER.info("min-value: " + minVal);
						LOGGER.info("max-value: " + maxVal);

						List<Long> numbers = new ArrayList<Long>();
						for (NavuContainer poolReq: ncsRoot.container("services").container("ura", "ura").list("integer").elem("my-range").list("request")) {
							ConfValue rv = null;
							try {
								rv = maapi.getElem(th, "/ncs:services/ura:ura/integer" + req.pool_key + "/request{" + poolReq.leaf("name").valueAsString() + "}/integer");
								numbers.add(Long.parseLong(String.valueOf(rv)));
							} catch (Exception e) {
							}
						}

						long newVal = minVal;
						if (allocMethod.getOrdinalValue() == 0) {
							newVal = Collections.max(numbers) + 1;
						} else if (allocMethod.getOrdinalValue() == 1) {

							Collections.sort(numbers);

							// TODO: we don't respect min-val or max-val
							// TODO: implement different allocation-methods
							// find next free number
							newVal = numbers.size();
							for(int i=0; i < numbers.size(); i++) {
								if(numbers.get(i) != (long)i) {
									newVal = i;
									break;
								}
							}

						}

                        // Write the result and redeploy
						ConfUInt64 integerValue = new ConfUInt64(newVal);
                        LOGGER.info("SET: " + req.path + "/integer -> " + integerValue);
                        wsess.setElem(integerValue, req.path + "/integer");

						ConfValue redeployPath = maapi.getElem(th, req.path + "/redeploy-service");
						LOGGER.info("redeploy-service: " + redeployPath);

                        redeploy(maapi, redeployPath + "/re-deploy");
                    }

                    else if (req.op == Operation.DEALLOCATE) {
                        //Deallocate the pseudowire ID

//                        try {
//                            ConfValue v = wsess.getElem(req.path + "/l2vpn:request-pwid/l2vpn:pw-id");
//                            int i = (int)((ConfUInt32)v).longValue();
//                            externalAllocator.deallocate_pwid(i);
//                            //wsess.delete(req.path + "/pw-id");
//                        } catch (Exception e) {
//                            LOGGER.error("",e);                      
//                        }

                    }

                }

                // Tell the subscription we are done 
                sub.sync(CdbSubscriptionSyncType.DONE_PRIORITY);
            }
        }
        catch (SocketException e) {
            // silence here, normal close (redeploy/reload package)
        }
        catch (Exception e) {
            LOGGER.error("",e );
        }
    }

    public void finish() {
        safeclose(cdb);
        safeclose(wcdb);
        try {
            maapi.getSocket().close();
        }
        catch (Exception e) {
        }
    }


    private void safeclose(Cdb s) {
        try {s.close();}
        catch (Exception ignore) {}
    }


    private enum Operation { ALLOCATE, DEALLOCATE }
    private enum Type { Integer }

    private class Request {
        Operation op;
        Type t;
        ConfPath path;
		ConfKey pool_key;
        ConfKey request_key;
    }

    private class Iter implements CdbDiffIterate {
        CdbSubscription cdbSub;

        Iter(CdbSubscription sub ) {
            this.cdbSub = sub;
        }

        public DiffIterateResultFlag iterate(
                ConfObject[] kp,
                DiffIterateOperFlag op,
                ConfObject oldValue,
                ConfObject newValue, Object initstate) {     

            @SuppressWarnings("unchecked")
            ArrayList<Request> reqs = (ArrayList<Request>) initstate;

//            LOGGER.info("Going into DiffIterate..."); 

            try {
                ConfPath p = new ConfPath(kp);
                LOGGER.info("ITER " + op + " " + p);
                // The kp array contains the keypath to the ConfObject in reverse order, for example:
                // ncs:services/l2vpn:l2vpn{newservice}/l2vpn:request-pwid -> ["l2vpn:request-pwid", "{newservice}", "l2vpn:l2vpn", "ncs:services" ]
                // Since we are subscribing to the changes on /ncs:services/l2vpn:l2vpn, the 3rd node from the end of the list always contains the service name (list key)
                Request r = new Request();
                r.path = p;
				if (kp[1].toString().equals("ura:request")) {
					if (op == DiffIterateOperFlag.MOP_CREATED) {
						LOGGER.info("Got a request! Pool: " + kp[0]);
						r.request_key = (ConfKey)kp[0];
						r.pool_key = (ConfKey)kp[2];
						r.op = Operation.ALLOCATE;
						r.t = Type.Integer;
						reqs.add(r);
					}
					//r.key = (ConfKey)kp[kp.length-4];
				}

                /* INSERT THE Iter.iterate() code here */
//				LOGGER.info("kp[0]:" + kp[0] + " kp[1]: " + kp[1] + " kp[2]: " + kp[2] + " kp[3]: " + kp[3]);

                // The container l2vpn:request-pwid is created in the service mapping code using sharedCreate
//                if ((op == DiffIterateOperFlag.MOP_CREATED) &&
//                        kp[1].toString().equals("request")) {
//                    r.op = Operation.CREATE; 
//                    r.t = Type.PWID;
//                    reqs.add(r);
//                }

                // The service instance (l2vpn list child node) is deleted
//                else if ((op == DiffIterateOperFlag.MOP_DELETED)) {
//                    r.op = Operation.DELETE;
//                    reqs.add(r);
//                }
            }
            catch (Exception e) {
                LOGGER.error("", e);
            }
            return DiffIterateResultFlag.ITER_RECURSE;

        } 
    }



    // redeploy MUST be done in another thread, if not system
    // hangs, since the CDB subscriber cannot do its work
    private void redeploy(Maapi m, String path) {
        Redeployer r = new Redeployer(m, path);
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
