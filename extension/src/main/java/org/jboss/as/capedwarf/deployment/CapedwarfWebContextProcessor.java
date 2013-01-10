/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.capedwarf.deployment;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.SetupAction;
import org.jboss.modules.Module;
import org.jboss.msc.service.ServiceName;
import org.jboss.vfs.VirtualFile;

/**
 * Prepare web context handles.
 *
 * @author <a href="mailto:ales.justin@jboss.org">Ales Justin</a>
 */
public class CapedwarfWebContextProcessor extends CapedwarfDeploymentUnitProcessor {

    protected void doDeploy(DeploymentPhaseContext phaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit unit = phaseContext.getDeploymentUnit();
        final VirtualFile deploymentRoot = unit.getAttachment(Attachments.DEPLOYMENT_ROOT).getRoot();
        final Module module = unit.getAttachment(Attachments.MODULE);
        if (module == null) {
            throw new DeploymentUnitProcessingException("No CL module: " + unit);
        }
        final ClassLoader classLoader = module.getClassLoader();
        final ClassLoader previous = SecurityActions.setTCCL(classLoader);
        try {
            // appengine-web.xml
            final InputStream appIs = getInputStream(deploymentRoot, "WEB-INF/appengine-web.xml", true);
            Object appConfig = read(appIs, classLoader, "org.jboss.capedwarf.common.config.AppEngineWebXmlParser");

            // capedwarf-web.xml
            final InputStream cdIs = getInputStream(deploymentRoot, "WEB-INF/capedwarf-web.xml", false);
            Object cdConfig = read(cdIs, classLoader, "org.jboss.capedwarf.common.config.CapedwarfConfigurationParser");

            // queue.xml
            final InputStream queueIs = getInputStream(deploymentRoot, "WEB-INF/queue.xml", false);
            Object queueConfig = read(queueIs, classLoader, "org.jboss.capedwarf.common.config.QueueXmlParser");

            // backends.xml
            final InputStream backendsIs = getInputStream(deploymentRoot, "WEB-INF/backends.xml", false);
            Object backendsConfig = read(backendsIs, classLoader, "org.jboss.capedwarf.common.config.BackendsXmlParser");

            final CapedwarfSetupAction cas = new CapedwarfSetupAction(classLoader, appConfig, cdConfig, queueConfig, backendsConfig);
            unit.addToAttachmentList(org.jboss.as.ee.component.Attachments.WEB_SETUP_ACTIONS, cas);
        } catch (DeploymentUnitProcessingException e) {
            throw e;
        } catch (Exception e) {
            throw new DeploymentUnitProcessingException(e);
        } finally {
            SecurityActions.setTCCL(previous);
        }
    }

    protected static InputStream getInputStream(VirtualFile root, String path, boolean mandatory) throws Exception {
        final VirtualFile child = root.getChild(path);
        if (child == null || child.exists() == false) {
            if (mandatory) {
                throw new DeploymentUnitProcessingException("No such file: " + path);
            } else {
                return null;
            }
        }
        return child.openStream();
    }

    protected static Object read(InputStream is, ClassLoader appCL, String parserClassName) throws Exception {
        Class<?> parserClass = appCL.loadClass(parserClassName);
        Method parse = parserClass.getDeclaredMethod("parse", InputStream.class);
        try {
            return parse.invoke(null, is);
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    private static class CapedwarfSetupAction implements SetupAction {
        private final ClassLoader appCL;
        private final Object appConfig;
        private final Object cdConfig;
        private final Object queueConfig;
        private final Object backendsConfig;

        private CapedwarfSetupAction(ClassLoader appCL, Object appConfig, Object cdConfig, Object queueConfig, Object backendsConfig) {
            this.appCL = appCL;
            this.appConfig = appConfig;
            this.cdConfig = cdConfig;
            this.queueConfig = queueConfig;
            this.backendsConfig = backendsConfig;
        }

        public void setup(Map<String, Object> properties) {
            setTL(appCL, "setAppEngineWebXml", appConfig);
            setTL(appCL, "setCapedwarfConfiguration", cdConfig);
            setTL(appCL, "setQueueXml", queueConfig);
            setTL(appCL, "setBackends", backendsConfig);

            invokeListener(appCL, "setup");
        }

        public void teardown(Map<String, Object> properties) {
            if (isContextSetup()) {
                invokeListener(appCL, "teardown");

                resetTL(appCL, "setAppEngineWebXml");
                resetTL(appCL, "setCapedwarfConfiguration");
                resetTL(appCL, "setQueueXml");
                resetTL(appCL, "setBackends");
            }
        }

        public int priority() {
            return Integer.MAX_VALUE - 1;
        }

        public Set<ServiceName> dependencies() {
            return Collections.emptySet();
        }

        protected boolean isContextSetup() {
            return (Boolean) invokeListener(appCL, "isSetup");
        }

        protected static void setTL(ClassLoader appCL, String method, Object value){
            invokeListener(appCL, method, new Class[]{Object.class}, new Object[]{value});
        }

        protected static void resetTL(ClassLoader appCL, String method){
            setTL(appCL, method, null);
        }

        protected static Object invokeListener(ClassLoader appCL, String method) {
            return invokeListener(appCL, method, new Class[0], new Object[0]);
        }

        protected static Object invokeListener(ClassLoader appCL, String method, Class<?>[] types, Object[] args) {
            try {
                Class<?> gaeListenerClass = appCL.loadClass("org.jboss.capedwarf.appidentity.GAEListener");
                Method m = gaeListenerClass.getDeclaredMethod(method, types);
                return m.invoke(null, args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
