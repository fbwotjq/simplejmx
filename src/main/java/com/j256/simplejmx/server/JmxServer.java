package com.j256.simplejmx.server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;

import com.j256.simplejmx.common.JmxResource;
import com.j256.simplejmx.common.JmxSelfNaming;
import com.j256.simplejmx.common.ObjectNameUtil;

/**
 * JMX server which allows classes to easily publish and un-publish themselves.
 * 
 * @author graywatson
 */
public class JmxServer {

	private Registry rmiRegistry;
	private int serverPort;
	private int registryPort;
	private JMXConnectorServer connector;
	private MBeanServer mbeanServer;

	/**
	 * Create a JMX server that will be set with the port using setters. Used with spring.
	 */
	public JmxServer() {
		// for spring
	}

	/**
	 * Create a JMX server running on a particular port.
	 */
	public JmxServer(int port) {
		this.registryPort = port;
	}

	/**
	 * Start our JMX service. The port must have already been called either here on in the {@link #JmxServer(int)}
	 * constructor before {@link #start()} is called.
	 */
	public synchronized void start() throws JMException {
		if (registryPort == 0) {
			throw new IllegalStateException("registry-port must be already set when JmxServer is initialized");
		}
		startRmiRegistry();
		startJmxService();
	}

	/**
	 * Stop the JMX server by closing the connector and unpublishing it from the RMI registry. This ignores any
	 * exceptions.
	 */
	public synchronized void stop() {
		try {
			stopThrow();
		} catch (JMException e) {
			// ignored
		}
	}

	/**
	 * Stop the JMX server by closing the connector and unpublishing it from the RMI registry. This throws a JMException
	 * on any issues.
	 */
	public synchronized void stopThrow() throws JMException {
		if (connector != null) {
			try {
				connector.stop();
			} catch (IOException e) {
				throw createJmException("Could not stop our Jmx connector server", e);
			} finally {
				connector = null;
			}
		}
		if (rmiRegistry != null) {
			try {
				UnicastRemoteObject.unexportObject(rmiRegistry, true);
			} catch (NoSuchObjectException e) {
				throw createJmException("Could not unexport our RMI registry", e);
			} finally {
				rmiRegistry = null;
			}
		}
	}

	/**
	 * Register the object parameter for exposure with JMX that implements self-naming. This calls through to
	 * {@link #register(Object)}.
	 */
	public void register(JmxSelfNaming selfNaming) throws JMException {
		register((Object) selfNaming);
	}

	/**
	 * Register the object parameter for exposure with JMX. The object passed in must have a {@link JmxResource}
	 * annotation or must implement {@link JmxSelfNaming}.
	 */
	public synchronized void register(Object obj) throws JMException {
		ObjectName objectName = extractJmxResourceObjName(obj);
		ReflectionMbean mbean;
		try {
			mbean = new ReflectionMbean(obj);
		} catch (Exception e) {
			throw createJmException("Could not build MBean object for: " + obj, e);
		}
		try {
			mbeanServer.registerMBean(mbean, objectName);
		} catch (Exception e) {
			throw createJmException("Registering JMX object " + objectName + " failed", e);
		}
	}

	/**
	 * Un-register the object parameter from JMX. Use the {@link #unregisterThrow(Object)} if you want to see the
	 * exceptions.
	 */
	public void unregister(Object obj) {
		try {
			unregisterThrow(obj);
		} catch (Exception e) {
			// ignored
		}
	}

	/**
	 * Un-register the object parameter from JMX but this throws exceptions. Use the {@link #unregister(Object)} if you
	 * want it to be silent.
	 */
	public synchronized void unregisterThrow(Object obj) throws JMException {
		ObjectName objectName = extractJmxResourceObjName(obj);
		mbeanServer.unregisterMBean(objectName);
	}

	/**
	 * This is actually calls {@link #setRegistryPort(int)}.
	 */
	public void setPort(int port) {
		setRegistryPort(port);
	}

	/**
	 * Set our port number to listen for JMX connections. In JMX terms, this is the "RMI registry port" but it is the
	 * port that you specify in jconsole to connect to the server. This must be set either here on in the
	 * {@link #JmxServer(int)} constructor before {@link #start()} is called.
	 */
	public void setRegistryPort(int registryPort) {
		this.registryPort = registryPort;
	}

	/**
	 * Chances are you should be using {@link #setPort(int)} or {@link #setRegistryPort(int)} unless you know what you
	 * are doing. This sets what JMX calls the "RMI server port". By default this does not have to be set and 1 plus the
	 * registry port will be used. When you specify a port number in jconsole this is not the port that should be
	 * specified -- see the registry port.
	 */
	public void setServerPort(int serverPort) {
		this.serverPort = serverPort;
	}

	private void startRmiRegistry() throws JMException {
		if (rmiRegistry == null) {
			try {
				rmiRegistry = LocateRegistry.createRegistry(registryPort);
			} catch (IOException e) {
				throw createJmException("Unable to create RMI registry on port " + registryPort, e);
			}
		}
	}

	private void startJmxService() throws JMException {
		if (connector == null) {
			JMXServiceURL url = null;
			if (serverPort == 0) {
				serverPort = registryPort + 1;
			}
			String urlString = "service:jmx:rmi://localhost:" + serverPort + "/jndi/rmi://:" + registryPort + "/jmxrmi";
			try {
				url = new JMXServiceURL(urlString);
			} catch (MalformedURLException e) {
				throw createJmException("Malformed service url created " + urlString, e);
			}
			try {
				connector =
						JMXConnectorServerFactory.newJMXConnectorServer(url, null,
								ManagementFactory.getPlatformMBeanServer());
			} catch (IOException e) {
				throw createJmException("Could not make our Jmx connector server", e);
			}
			try {
				connector.start();
			} catch (IOException e) {
				connector = null;
				throw createJmException("Could not start our Jmx connector server", e);
			}
			mbeanServer = connector.getMBeanServer();
		}
	}

	private ObjectName extractJmxResourceObjName(Object obj) {
		JmxResource jmxResource = obj.getClass().getAnnotation(JmxResource.class);
		if (obj instanceof JmxSelfNaming) {
			return ObjectNameUtil.makeObjectName(jmxResource, (JmxSelfNaming) obj);
		} else {
			if (jmxResource == null) {
				throw new IllegalArgumentException(
						"Registered class must implement JmxSelfNaming or have JmxResource annotation");
			}
			return ObjectNameUtil.makeObjectName(jmxResource, obj);
		}
	}

	private JMException createJmException(String message, Exception e) {
		JMException jmException = new JMException(message);
		jmException.initCause(e);
		return jmException;
	}
}
