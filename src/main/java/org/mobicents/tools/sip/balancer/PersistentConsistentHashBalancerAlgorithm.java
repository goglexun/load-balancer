package org.mobicents.tools.sip.balancer;

import gov.nist.javax.sip.header.SIPHeader;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.sip.address.SipURI;
import javax.sip.header.RouteHeader;
import javax.sip.message.Message;
import javax.sip.message.Request;

import org.jboss.cache.Cache;
import org.jboss.cache.CacheFactory;
import org.jboss.cache.DefaultCacheFactory;
import org.jboss.cache.Fqn;
import org.jboss.cache.notifications.annotation.CacheListener;
import org.jboss.cache.notifications.annotation.NodeModified;
import org.jboss.cache.notifications.annotation.ViewChanged;
import org.jboss.cache.notifications.event.Event;
import org.jboss.cache.notifications.event.ViewChangedEvent;

/**
 * Persistent Consistent Hash algorithm - see http://docs.google.com/present/view?id=dc5jp5vx_89cxdvtxcm Example algorithms section
 * @author vralev
 *
 */
@CacheListener
public class PersistentConsistentHashBalancerAlgorithm extends DefaultBalancerAlgorithm {
	private static Logger logger = Logger.getLogger(PersistentConsistentHashBalancerAlgorithm.class.getCanonicalName());
	
	protected String headerName;
	
	protected Cache cache;
	
	// And we also keep a copy in the array because it is faster to query by index
	private Object[] nodesArray = new Object[]{};
	
	private boolean nodesAreDirty = true;
	
	public PersistentConsistentHashBalancerAlgorithm() {
			this.headerName = "Call-ID";
	}
	
	public PersistentConsistentHashBalancerAlgorithm(String headerName) {
		this.headerName = headerName;
	}

	public SIPNode processExternalRequest(Request request) {
		Integer nodeIndex = hashHeader(request);
		if(nodeIndex<0) {
			return null;
		} else {
			BalancerContext balancerContext = getBalancerContext();
			if(nodesAreDirty) {
				synchronized(this) {
					syncNodes();
				}
			}
			try {
				SIPNode node = (SIPNode) nodesArray[nodeIndex];
				if(node != null) {
					//Adding Route Header pointing to the node the sip balancer wants to forward to
					SipURI routeSipUri;
					try {
						routeSipUri = balancerContext.addressFactory
						.createSipURI(null, node.getIp());

						routeSipUri.setPort(node.getPort());
						routeSipUri.setLrParam();
						final RouteHeader route = balancerContext.headerFactory.createRouteHeader(
								balancerContext.addressFactory.createAddress(routeSipUri));
						request.addFirst(route);
					} catch (Exception e) {
						throw new RuntimeException("Error adding route header", e);
					}
				}
				return node;
			} catch (Exception e) {
				return null;
			}
		}
	}
	
	@NodeModified
	public void modified(Event event) {
		logger.fine(event.toString());
	}

	public synchronized void nodeAdded(SIPNode node) {
		addNode(node);
		syncNodes();
	}
	
	private void addNode(SIPNode node) {
		Fqn nodes = Fqn.fromString("/BALANCER/NODES");
		cache.put(nodes, node, "");
		dumpNodes();
	}

	public synchronized void nodeRemoved(SIPNode node) {
		dumpNodes();
	}
	
	private void dumpNodes() {
		logger.info("The following nodes are in cache right now:");
		for(Object object : nodesArray) {
			SIPNode node = (SIPNode) object;
			logger.info(node.toString() + " [ALIVE:" + isAlive(node) + "]");
		}
	}
	
	private boolean isAlive(SIPNode node) {
		if(getBalancerContext().nodes.contains(node)) return true;
		return false;
	}
	
	private Integer hashHeader(Message message) {
		String headerValue = ((SIPHeader) message.getHeader(headerName))
		.getValue();

		if(nodesArray.length == 0) throw new RuntimeException("No Application Servers registered. All servers are dead.");
		
		int nodeIndex = Math.abs(headerValue.hashCode()) % nodesArray.length;

		SIPNode computedNode = (SIPNode) nodesArray[nodeIndex];
		
		if(!isAlive(computedNode)) {
			// If the computed node is dead, find a new one
			for(int q = 0; q<nodesArray.length; q++) {
				nodeIndex = (nodeIndex + 1) % nodesArray.length;
				if(isAlive(((SIPNode)nodesArray[nodeIndex]))) {
					break;
				}
			}
		}
		
		if(isAlive((SIPNode)nodesArray[nodeIndex])) {
			return nodeIndex;
		} else {
			return -1;
		}
		
	}
	
	@ViewChanged
	public void viewChanged(ViewChangedEvent event) {
		logger.info(event.toString());
	}
	
	public void init() {
		CacheFactory cacheFactory = new DefaultCacheFactory();
		InputStream configurationInputStream = null;
		String configFile = getProperties().getProperty("persistentConsistentHashCacheConfiguration");
		if(configFile != null) {
			logger.info("Try to use cache configuration from " + configFile);
			try {
				configurationInputStream = new FileInputStream(configFile);
			} catch (FileNotFoundException e1) {
				logger.log(Level.SEVERE, "File not found", e1);
				throw new RuntimeException(e1);
			}
		} else {
			logger.info("Using default cache settings");
			configurationInputStream = this.getClass().getClassLoader().getResourceAsStream("META-INF/PHA-balancer-cache.xml");
			if(configurationInputStream == null) throw new RuntimeException("Problem loading resource META-INF/PHA-balancer-cache.xml");
		}
	


		Cache cache = cacheFactory.createCache(configurationInputStream);
		cache.addCacheListener(this);
		cache.create();
		cache.start();
		this.cache = cache;
		
		for (SIPNode node : getBalancerContext().nodes) {
			addNode(node);
		}
		syncNodes();

		String headerName = getProperties().getProperty("consistentHashAffinityHeader");
		if(headerName != null) {
			this.headerName = headerName;
		}
	}
	
	private void syncNodes() {
		Set nodes = cache.getKeys("/BALANCER/NODES");
		if(nodes != null) {
			ArrayList nodeList = new ArrayList();
			nodeList.addAll(nodes);
			Collections.sort(nodeList);
			this.nodesArray = nodeList.toArray();
		}
		dumpNodes();
	}

}
