package net.spritelink.uratest;

import net.spritelink.uratest.namespaces.*;
import java.util.Properties;
import com.tailf.conf.*;
import com.tailf.navu.*;
import com.tailf.ncs.annotations.Resource;
import com.tailf.ncs.annotations.ResourceType;
import com.tailf.ncs.annotations.Scope;
import com.tailf.ncs.ns.Ncs;
import com.tailf.dp.*;
import com.tailf.dp.annotations.*;
import com.tailf.dp.proto.*;
import com.tailf.dp.services.*;
import com.tailf.cdb.Cdb;

import org.apache.log4j.Logger;

public class uraTest {
    private static Logger LOGGER = Logger.getLogger(uraTest.class);

    // Use the ResourceManager to inject an instance of the Cdb
    @Resource(type=ResourceType.CDB, scope=Scope.CONTEXT, qualifier="id-allocator")
    private Cdb cdb;

    /**
     * Create callback method.
     * This method is called when a service instance committed due to a create
     * or update event.
     *
     * This method returns a opaque as a Properties object that can be null.
     * If not null it is stored persistently by Ncs.
     * This object is then delivered as argument to new calls of the create
     * method for this service (fastmap algorithm).
     * This way the user can store and later modify persistent data outside
     * the service model that might be needed.
     *
     * @param context - The current ServiceContext object
     * @param service - The NavuNode references the service node.
     * @param ncsRoot - This NavuNode references the ncs root.
     * @param opaque  - Parameter contains a Properties object.
     *                  This object may be used to transfer
     *                  additional information between consecutive
     *                  calls to the create callback.  It is always
     *                  null in the first call. I.e. when the service
     *                  is first created.
     * @return Properties the returning opaque instance
     * @throws DpCallbackException
     */

    @ServiceCallback(servicePoint="ura-test-servicepoint",
        callType=ServiceCBType.CREATE)
    public Properties create(ServiceContext context,
                             NavuNode service,
                             NavuNode ncsRoot,
                             Properties opaque)
                             throws NavuException, DpCallbackException {
		LOGGER.info("uraTest create() called");

		String intRangeName = service.leaf("integer-range").valueAsString();
		NavuContainer intRange = ncsRoot.container("ncs", "services").container("ura", "ura").list("integer").sharedCreate(intRangeName);
		int numRequests = Integer.parseInt(service.leaf("num-requests").valueAsString());

		for (int i = 0; i < numRequests; i++) {
//			LOGGER.info("requesting integer allocation from: " + intRangeName);
//			LOGGER.info("service keyPath: " + service.getKeyPath());

			String reqName = service.leaf("name").valueAsString() + "_" + i;

			NavuContainer request = intRange.list("request").sharedCreate(reqName);

			request.leaf("redeploy-service").sharedSet(new ConfBuf(service.getKeyPath()));

			NavuContainer base = new NavuContainer(new NavuContext(cdb));
			NavuContainer cdbRoot = base.container(Ncs.hash);

			ConfUInt64 id = null;
			try {
				id = (ConfUInt64) cdbRoot.container(Ncs._services_)
					.container("ura", "ura")
					.list("integer")
					.elem(intRangeName)
					.list("request")
					.elem(reqName)
					.leaf("integer").value();
			} catch (NullPointerException e) {}
			LOGGER.info(service.getKeyPath() + " CDB: " + id);

			// Demonstrate that you can't reach CDB oper value through ncsRoot
			// which is accessing CDB conf
//			LOGGER.info("current value from NAVU: " + request.leaf("integer").valueAsString());
		}

        return opaque;
    }


    /**
     * Init method for selftest action
     */
    @ActionCallback(callPoint="ura-test-self-test", callType=ActionCBType.INIT)
    public void init(DpActionTrans trans) throws DpCallbackException {
    }
}
