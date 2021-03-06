package org.ofbiz.service;

import org.ofbiz.base.util.Debug;
import org.ofbiz.entity.transaction.GenericTransactionException;
import org.ofbiz.entity.transaction.TransactionFactoryLoader;
import org.ofbiz.entity.transaction.TransactionUtil;

import javax.transaction.*;
import java.util.*;

/**
 * This class is used to execute services when a transaction is either 
 * committed or rolled back.  It should generally be accessed via
 * LocalDispatcher's addCommitService and addRollbackService methods
 * or by using the service ECA event attribute values global-commit,
 * global-rollback or global-commit-post-run
 *
 */
public class ServiceSynchronization implements Synchronization {

    public static final String MODULE = ServiceSynchronization.class.getName();

    private static Map<Transaction, ServiceSynchronization> syncingleton = new WeakHashMap<Transaction, ServiceSynchronization>();
    private List<ServiceExecution> services = new ArrayList<ServiceExecution>();

    public static void registerCommitService(DispatchContext dctx, String serviceName, String runAsUser, Map<String, ? extends Object> context, boolean async, boolean persist) throws GenericServiceException {
        ServiceSynchronization sync = ServiceSynchronization.getInstance();
        if (sync != null) {
            sync.services.add(new ServiceExecution(dctx, serviceName, runAsUser, context, async, persist, false));
        }
    }

    public static void registerRollbackService(DispatchContext dctx, String serviceName, String runAsUser, Map<String, ? extends Object> context, boolean async, boolean persist) throws GenericServiceException {
        ServiceSynchronization sync = ServiceSynchronization.getInstance();
        if (sync != null) {
            sync.services.add(new ServiceExecution(dctx, serviceName, runAsUser, context, async, persist, true));
        }
    }

    protected static ServiceSynchronization getInstance() throws GenericServiceException {
        ServiceSynchronization sync = null;
        try {
            Transaction transaction = TransactionFactoryLoader.getInstance().getTransactionManager().getTransaction();
            synchronized (transaction) {
                sync = syncingleton.get(transaction);
                if (sync == null) {
                    sync = new ServiceSynchronization();
                    transaction.registerSynchronization(sync);
                    syncingleton.put(transaction, sync);
                }
            }
        } catch (SystemException e) {
            throw new GenericServiceException(e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new GenericServiceException(e.getMessage(), e);
        } catch (RollbackException e) {
            throw new GenericServiceException(e.getMessage(), e);
        }
        return sync;
    }

    @Override
    public void afterCompletion(int status) {
        for (ServiceExecution serviceExec : this.services) {
            serviceExec.runService(status);
        }
    }

    @Override
    public void beforeCompletion() {

    }

    static class ServiceExecution {
        protected DispatchContext dctx = null;
        protected String serviceName;
        protected String runAsUser = null;
        protected Map<String, ? extends Object> context = null;
        protected boolean rollback = false;
        protected boolean persist = true;
        protected boolean async = false;

        ServiceExecution(DispatchContext dctx, String serviceName, String runAsUser, Map<String, ? extends Object> context, boolean async, boolean persist, boolean rollback) {
            this.dctx = dctx;
            this.serviceName = serviceName;
            this.runAsUser = runAsUser;
            this.context = context;
            this.async = async;
            this.persist = persist;
            this.rollback = rollback;
        }

        protected void runService(int status) {
            if ((status == Status.STATUS_COMMITTED && !rollback) || (status == Status.STATUS_ROLLEDBACK && rollback)) {
                Thread thread = new Thread() {
                    @Override
                    public void run() {
                        String msgPrefix = null;
                        if (rollback) {
                            msgPrefix = "[Rollback] ";
                        } else {
                            msgPrefix = "[Commit] ";
                        }

                        boolean beganTx;
                        try {
                            // begin the new tx
                            beganTx = TransactionUtil.begin();
                            // configure and run the service
                            try {
                                // obtain the model and get the valid context
                                ModelService model = dctx.getModelService(serviceName);
                                Map<String, Object> thisContext;
                                if (model.validate) {
                                    thisContext = model.makeValid(context, ModelService.IN_PARAM);
                                } else {
                                    thisContext = new HashMap<String, Object>();
                                    thisContext.putAll(context);
                                }

                                // set the userLogin object
                                thisContext.put("userLogin", ServiceUtil.getUserLogin(dctx, thisContext, runAsUser));
                                if (async) {
                                    if (Debug.infoOn()) Debug.logInfo(msgPrefix + "Invoking [" + serviceName + "] via runAsync", MODULE);
                                    dctx.getDispatcher().runAsync(serviceName, thisContext, persist);
                                } else {
                                    if (Debug.infoOn()) Debug.logInfo(msgPrefix + "Invoking [" + serviceName + "] via runSyncIgnore", MODULE);
                                    dctx.getDispatcher().runSyncIgnore(serviceName, thisContext);
                                }
                            } catch (Throwable t) {
                                Debug.logError(t, "Problem calling " + msgPrefix + "service : " + serviceName + " / " + context, MODULE);
                                try {
                                    TransactionUtil.rollback(beganTx, t.getMessage(), t);
                                } catch (GenericTransactionException e) {
                                    Debug.logError(e, MODULE);
                                }

                            } finally {
                                // commit the transaction
                                try {
                                    TransactionUtil.commit(beganTx);
                                } catch (GenericTransactionException e) {
                                    Debug.logError(e, MODULE);
                                }
                            }
                        } catch (GenericTransactionException e) {
                            Debug.logError(e, MODULE);
                        }

                    }
                };
                thread.start();
            }
        }
    }

}
