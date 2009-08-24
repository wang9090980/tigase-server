/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2007 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 *
 * $Rev$
 * Last modified by $Author$
 * $Date$
 */

package tigase.cluster;

import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Timer;

//import tigase.cluster.methodcalls.SessionTransferMC;
import tigase.server.Packet;
import tigase.server.Command;
import tigase.util.JIDUtils;
import tigase.xmpp.StanzaType;
import tigase.xmpp.Authorization;
import tigase.xmpp.XMPPResourceConnection;
import tigase.xmpp.ConnectionStatus;
import tigase.xmpp.XMPPSession;
import tigase.server.xmppsession.SessionManager;
import tigase.xml.Element;


/**
 * Class SessionManagerClusteredOld
 *
 *
 * Created: Tue Nov 22 07:07:11 2005
 *
 * @author <a href="mailto:artur.hefczyc@tigase.org">Artur Hefczyc</a>
 * @version $Rev$
 */
public class SessionManagerClusteredOld extends SessionManager
	implements ClusteredComponent {

	public static final String STRATEGY_CLASS_PROPERTY = "--sm-cluster-strategy-class";
	public static final String STRATEGY_CLASS_PROP_KEY = "cluster-strategy-class";
	public static final String STRATEGY_CLASS_PROP_VAL =
					"tigase.cluster.strategy.SMNonCachingAllNodes";

	/**
   * Variable <code>log</code> is a class logger.
   */
  private static final Logger log =
    Logger.getLogger(SessionManagerClusteredOld.class.getName());

	private static final String USER_ID = "userId";
	private static final String SM_ID = "smId";
	private static final String CREATION_TIME = "creationTime";
	private static final String ERROR_CODE = "errorCode";

	private static final String XMPP_SESSION_ID = "xmppSessionId";
	private static final String RESOURCE = "resource";
	private static final String CONNECTION_ID = "connectionId";
	private static final String PRIORITY = "priority";
	private static final String TOKEN = "token";
	private static final String TRANSFER = "transfer";
	private static final String AUTH_TIME = "auth-time";

	//	private SessionTransferMC sessionTransferMC = null;

	private Timer delayedTasks = null;
	//private Set<String> broken_nodes = new LinkedHashSet<String>();
	private ClusteringStrategyIfc strategy = null;
	//	private ArrayList<MethodCall> methods = new ArrayList<MethodCall>();

	@SuppressWarnings("unchecked")
	@Override
	public void processPacket(Packet packet) {
		if (log.isLoggable(Level.FINEST)) {
			log.finest("Received packet: " + packet.toString());
		}
		if (packet.getElemName() == ClusterElement.CLUSTER_EL_NAME
			&& packet.getElement().getXMLNS() == ClusterElement.XMLNS) {
			processClusterPacket(packet);
			return;
		}

		if (packet.isCommand() && processCommand(packet)) {
			packet.processedBy("SessionManager");
			// No more processing is needed for command packet
			return;
		} // end of if (pc.isCommand())
		XMPPResourceConnection conn = getXMPPResourceConnection(packet);
		if (conn == null
			&& (isBrokenPacket(packet) || processAdminsOrDomains(packet)
				|| sentToNextNode(packet))) {
			return;
		}
		if (conn != null) {
			switch (conn.getConnectionStatus()) {
			case ON_HOLD:
				LinkedList<Packet> packets =
          (LinkedList<Packet>)conn.getSessionData(SESSION_PACKETS);
				if (packets == null) {
					packets = new LinkedList<Packet>();
					conn.putSessionData(SESSION_PACKETS, packets);
				}
				packets.offer(packet);
				if (log.isLoggable(Level.FINEST)) {
					log.finest("Packet put on hold: " + packet.toString());
				}
				return;
			case REDIRECT:
				sendPacketRedirect(packet, (String)conn.getSessionData("redirect-to"));
//				packet.setTo((String)conn.getSessionData("redirect-to"));
//				fastAddOutPacket(packet);
				return;
			}
		}
		processPacket(packet, conn);
	}

	@SuppressWarnings("unchecked")
	protected void processPacket(ClusterElement packet) {
		List<Element> elems = packet.getDataPackets();
		//String packet_from = packet.getDataPacketFrom();
		if (elems != null && elems.size() > 0) {
			for (Element elem: elems) {
				Packet el_packet = new Packet(elem);
				//processPacket(el_packet);
				//el_packet.setFrom(packet_from);
				XMPPResourceConnection conn = getXMPPResourceConnection(el_packet);
				if (conn != null || !sentToNextNode(packet)) {
					if (conn != null) {
						switch (conn.getConnectionStatus()) {
						case ON_HOLD:
							LinkedList<Packet> packets =
											(LinkedList<Packet>) conn.getSessionData(SESSION_PACKETS);
							if (packets == null) {
								packets = new LinkedList<Packet>();
								conn.putSessionData(SESSION_PACKETS, packets);
							}
							packets.offer(el_packet);
							log.finest("Packet put on hold: " + el_packet.toString());
							return;
						case REDIRECT:
							sendPacketRedirect(el_packet, 
											(String) conn.getSessionData("redirect-to"));
//							el_packet.setTo((String)conn.getSessionData("redirect-to"));
//							fastAddOutPacket(el_packet);							
							return;
						}
					}
					processPacket(el_packet, conn);
				}
			}
		} else {
			log.finest("Empty packets list in the cluster packet: "
				+ packet.toString());
		}
	}

	private void sendPacketRedirect(Packet packet, String destination) {
		Map<String, String> params = new LinkedHashMap<String, String>();
		params.put("to", destination);
		params.put("from", packet.getFrom());
		ClusterElement redirect =
						ClusterElement.createClusterMethodCall(getComponentId(),
						destination, StanzaType.set,
						ClusterMethods.PACKET_REDIRECT.toString(), params);
		redirect.addDataPacket(packet);
		Packet pack_red = new Packet(redirect.getClusterElement());
		fastAddOutPacket(pack_red);
		log.finest("Packet redirected: " + pack_red.toString());
	}

	protected void processClusterPacket(Packet packet) {
		final ClusterElement clel = new ClusterElement(packet.getElement());
		//clel.addVisitedNode(getComponentId());
		switch (packet.getType()) {
		case set:
			if (clel.getMethodName() == null) {
				processPacket(clel);
			}
			if (ClusterMethods.PACKET_REDIRECT.toString().equals(clel.getMethodName())) {
				for (Element elem : clel.getDataPackets()) {
					Packet pack = new Packet(elem);
					pack.setTo(clel.getMethodParam("to"));
					pack.setFrom(clel.getMethodParam("from"));
					XMPPResourceConnection conn = getXMPPResourceConnection(pack);
					if (conn == null) {
						ClusterElement response = clel.createMethodResponse(packet.getTo(),
										packet.getFrom(),	StanzaType.error, null);
						Packet resp_pack = new Packet(response.getClusterElement());
						fastAddOutPacket(resp_pack);
						log.info("No local session for redirected packet, sending error back: " +
										resp_pack.toString());
					} else {
						processPacket(pack, conn);
					}
				}
				return;
			}
			if (ClusterMethods.SESSION_TRANSFER.toString().equals(clel.getMethodName())) {
				Set<String> cancel_nodes = new LinkedHashSet<String>();
				String node_found = null;
				String userId = clel.getMethodParam(USER_ID);
				XMPPSession session = getSession(userId);
				String connectionId = clel.getMethodParam(CONNECTION_ID);
				XMPPResourceConnection conn = null;
				if (session != null) {
					conn = session.getResourceForConnectionId(connectionId);
				}
				//ClusterElement result = null;
				if (getComponentId().equals(clel.getFirstNode())) {
					log.finest("Session transfer request came back to me....");
					// Ok, the request has came back to me, let's look what to do now....
					// First let's check whether any of the nodes has decided to accept
					// the transfer:
					// This is a set of nodes we will send a transfer cancell later on
					long time = 0;
					long hash = 0;
					for (String node: clel.getVisitedNodes()) {
						if (clel.getMethodResultVal(node + "-CREATED") != null) {
							long tmp_time = clel.getMethodResultVal(node + "-" + AUTH_TIME, 0);
							long tmp_hash = clel.getMethodResultVal(
								node + "-HASH-" + XMPP_SESSION_ID, 0);
							log.finest("Node: " + node + " responded with: "
								+ clel.getMethodResultVal(node + "-CREATED")
								+ ", tmp_time: " + tmp_time
								+ ", tmp_hash: " + tmp_hash);
							boolean replace_node = false;
							if (tmp_time == time) {
								if (tmp_hash > hash) {
									replace_node = true;
								}
							} else {
								if (tmp_time > time) {
									replace_node = true;
								}
							}
							if (replace_node) {
								if (node_found != null) {
									log.finest("Addeding node to cancel_nodes: " + node_found);
									cancel_nodes.add(node_found);
								}
								node_found = node;
								hash = tmp_hash;
								time = tmp_time;
							} else {
								log.finest("Addeding node to cancel_nodes: " + node);
								cancel_nodes.add(node);
							}
						}
					}
					if (node_found != null) {
						// This is where we want to do the user session transfer then
						if (session != null) {
							if (conn != null) {
								Map<String, String> res_vals = new LinkedHashMap<String, String>();
								res_vals.put(TRANSFER, "accept");
								ClusterElement result = clel.createMethodResponse(getComponentId(),
									node_found, StanzaType.result, res_vals);
								fastAddOutPacket(new Packet(result.getClusterElement()));
								conn.putSessionData("redirect-to", node_found);
								sendAllOnHold(conn);

								String xmpp_sessionId = clel.getMethodParam(XMPP_SESSION_ID);
								Packet redirect = Command.REDIRECT.getPacket(node_found,
									connectionId, StanzaType.set, "1", Command.DataType.submit);
								Command.addFieldValue(redirect, "session-id", xmpp_sessionId);
								fastAddOutPacket(redirect);
							} else {
								// Ups, the user has disconnected, send session transfer to all
								log.finest("Addeding node to cancel_nodes: " + node_found);
								cancel_nodes.add(node_found);
								log.fine("The user connection doesn't exist: " + userId
									+ ", connectionId: " + connectionId);
							}
						} else {
							// Ups, the user has disconnected, send session transfer to all
							log.finest("Addeding node to cancel_nodes: " + node_found);
							cancel_nodes.add(node_found);
							log.fine("The user session doesn't exist: " + userId);
						}
					} else {
						// Set status to NORMALL, user is not logged in on any other node.
						if (conn != null) {
							log.finest("Set status to NORMAL and send all ON_HOLD");
							conn.setConnectionStatus(ConnectionStatus.NORMAL);
							sendAllOnHold(conn);
						} else {
							log.fine("The user connection doesn't exist: " + userId
								+ ", connectionId: " + connectionId);
						}
					}
					if (cancel_nodes.size() > 0) {
						// Send session transfer to all.
						Map<String, String> res_vals = new LinkedHashMap<String, String>();
						res_vals.put(TRANSFER, "cancel");
						for (String node: cancel_nodes) {
							ClusterElement result = clel.createMethodResponse(getComponentId(),
								node, StanzaType.result, res_vals);
							log.finest("Sending sesstion transfer CANCEL to node: " + node);
							fastAddOutPacket(new Packet(result.getClusterElement()));
						}
					}
				} else {
					// A request from some other node, maybe the user session should be
					// transfered here...
					ClusterElement result = ClusterElement.createForNextNode(clel,
						strategy.getNodesForJid(userId),	getComponentId());
					if (session != null) {
						conn = session.getOldestConnection();
						boolean transfer_in = false;
						switch (conn.getConnectionStatus()) {
						case ON_HOLD:
							long local_auth_time = conn.getAuthTime();
							long remote_auth_time = clel.getMethodParam(AUTH_TIME, 0L);
							if (local_auth_time == remote_auth_time) {
								transfer_in = (conn.getSessionId().hashCode() >
									clel.getMethodParam(XMPP_SESSION_ID).hashCode());
							} else {
								transfer_in = (local_auth_time > remote_auth_time);
							}
							break;
						case REDIRECT:
							transfer_in = false;
							break;
						case NORMAL:
							transfer_in = true;
							break;
						default:
							break;
						}
						if (transfer_in) {
							addTempSession(clel);
							result.addMethodResult(getComponentId() + "-" + AUTH_TIME,
								"" + conn.getAuthTime());
							result.addMethodResult(getComponentId() + "-HASH-" + XMPP_SESSION_ID,
								"" + conn.getSessionId().hashCode());
							result.addMethodResult(getComponentId() + "-STATUS",
								"" + conn.getConnectionStatus());
							result.addMethodResult(getComponentId() + "-CREATED",
								"" + true);
						}
					} else {
						// Do nothing really, just forward the request to a next node
					}
					fastAddOutPacket(new Packet(result.getClusterElement()));
				}
			}
			break;
		case result:
			if (ClusterMethods.SESSION_TRANSFER.toString().equals(clel.getMethodName())) {
				String transfer = clel.getMethodResultVal(TRANSFER);
				if (transfer == null) {
					log.warning("Incorrect response for the session transfer: "
						+ packet.toString());
					return;
				}
				if (transfer.equals("accept")) {
					String userId = clel.getMethodParam(USER_ID);
					XMPPSession session = getSession(userId);
					if (session == null) {
						// Ups, something wrong happened, let's record this in the log
						// file for now...
						log.warning("User session does not exist for the request to complete the user transfer: " + packet.toString());
						return;
					}
					String connectionId = clel.getMethodParam(CONNECTION_ID);
					XMPPResourceConnection conn =
            session.getResourceForConnectionId(connectionId);
					if (conn == null) {
						// Ups, something wrong happened, let's record this in the log
						// file for now...
						log.warning("User connection does not exist for the request to complete the user transfer: " + packet.toString());
						return;
					}
					String token = (String)conn.getSessionData(TOKEN);
					String xmpp_sessionId = conn.getSessionId();
					Authorization auth_res = null;
					try {
						auth_res = conn.loginToken(userId, xmpp_sessionId, token);
					} catch (Exception e) {
						log.log(Level.WARNING, "Token authentication unsuccessful.", e);
						auth_res = Authorization.NOT_AUTHORIZED;
					}
					if (auth_res == Authorization.AUTHORIZED) {
						log.finest("SESSION_TRANSFER received SET request, userId: " + userId
							+ ", xmpp_sessionId: " + xmpp_sessionId
							+ ", connectionId: " + connectionId + ", auth_res: " + auth_res);
					} else {
						log.finest("SESSION_TRANSFER authorization failed: " + auth_res
							+ ", userId: " + userId);
						closeConnection(conn.getConnectionId(), true);
						Packet close = Command.CLOSE.getPacket(getComponentId(),
							connectionId, StanzaType.set, "1");
						fastAddOutPacket(close);
					}
					conn.setConnectionStatus(ConnectionStatus.NORMAL);
// 					Packet redirect = Command.REDIRECT.getPacket(getComponentId(),
// 						connectionId, StanzaType.set, "1", "submit");
// 					Command.addFieldValue(redirect, "session-id", xmpp_sessionId);
// 					fastAddOutPacket(redirect);

// 					Map<String, String> res_vals = new LinkedHashMap<String, String>();
// 					res_vals.put(TRANSFER, "cancel");
// 					ClusterElement result = clel.createMethodResponse(getComponentId(),
// 						"result", res_vals);
// 					fastAddOutPacket(new Packet(result.getClusterElement()));
					return;
				}
				if (transfer.equals("cancel")) {
					String connectionId = clel.getMethodParam(CONNECTION_ID);
					closeConnection(connectionId, true);
					return;
				}
				log.warning("Incorrect response for the session transfer: "
					+ packet.toString());
			}
			break;
		case error:
			if (ClusterMethods.PACKET_REDIRECT.toString().equals(clel.getMethodName())) {
				for (Element elem : clel.getDataPackets()) {
					Packet pack = new Packet(elem);
					pack.setTo(clel.getMethodParam("to"));
					pack.setFrom(clel.getMethodParam("from"));
					XMPPResourceConnection conn = getXMPPResourceConnection(pack);
					if (conn == null) {
						// Just ignore.
						log.info("No local session for redirect error packet, ignoring: " +
										packet.toString());
					} else {
            // Remove the local session with redirect, the session on the other
						// node doesn't exist anymore anyway
						log.info("Packet redirect error, removing local session: " +
										packet.toString());
						closeConnection(conn.getConnectionId(), true);
					}
				}
				return;
			}
			String from = packet.getElemFrom();
			clel.addVisitedNode(from);
//			if (cluster_nodes.remove(from)) {
//				broken_nodes.add(from);
//			}
			processPacket(clel);
			break;
		default:
			break;
		}
	}

	private void addTempSession(ClusterElement clel) {
		String connectionId = clel.getMethodParam(CONNECTION_ID);
		String userId = clel.getMethodParam(USER_ID);
		String domain = JIDUtils.getNodeHost(userId);
		XMPPResourceConnection res_con = createUserSession(connectionId, domain);
		res_con.setConnectionStatus(ConnectionStatus.TEMP);
		String xmpp_sessionId = clel.getMethodParam(XMPP_SESSION_ID);
		res_con.setSessionId(xmpp_sessionId);
		String token = clel.getMethodParam(TOKEN);
		res_con.putSessionData(TOKEN, token);
	}

	protected boolean sentToNextNode(ClusterElement clel) {
		String userId = clel.getMethodParam(USER_ID);
		ClusterElement next_clel = ClusterElement.createForNextNode(clel,
			strategy.getNodesForJid(userId), getComponentId());
		if (next_clel != null) {
			fastAddOutPacket(new Packet(next_clel.getClusterElement()));
			return true;
		} else {
			return false;
		}
	}

	protected boolean sentToNextNode(Packet packet) {
		String userId = JIDUtils.getNodeID(packet.getElemTo());
		String cluster_node = getFirstClusterNode(userId);
		if (cluster_node != null) {
			String sess_man_id = getComponentId();
			ClusterElement clel = new ClusterElement(sess_man_id, cluster_node,
							StanzaType.set, packet);
			clel.addVisitedNode(sess_man_id);
			fastAddOutPacket(new Packet(clel.getClusterElement()));
			return true;
		}
		return false;
	}

	@Override
	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);
		String strategy_class = (String) props.get(STRATEGY_CLASS_PROP_KEY);
		try {
			ClusteringStrategyIfc strategy_tmp =
							(ClusteringStrategyIfc) Class.forName(strategy_class).newInstance();
			strategy_tmp.setProperties(props);
			//strategy_tmp.init(getName());
			strategy = strategy_tmp;
		} catch (Exception e) {
			log.log(Level.SEVERE,
							"Can not create VHost repository instance for class: " +
							strategy_class, e);
		}

// 		init();
	}

// 	private void init() {
// 		this.sessionTransferMC = new SessionTransferMC();
// 	}

// 	private <T extends MethodCall> T registerMethodCall(final T methodCall) {
// 		log.config("Registering method call: "
// 			+ methodCall.getClass().getCanonicalName());
// 		this.methods.add(methodCall);
// 		return methodCall;
// 	}

	@Override
	public Map<String, Object> getDefaults(Map<String, Object> params) {
		Map<String, Object> props = super.getDefaults(params);
		String strategy_class = (String)params.get(STRATEGY_CLASS_PROPERTY);
		if (strategy_class == null) {
			strategy_class = STRATEGY_CLASS_PROP_VAL;
		}
		props.put(STRATEGY_CLASS_PROP_KEY, strategy_class);
		try {
			ClusteringStrategyIfc strat_tmp =
							(ClusteringStrategyIfc) Class.forName(strategy_class).newInstance();
			Map<String, Object> strat_defs = strat_tmp.getDefaults(params);
			if (strat_defs != null) {
				props.putAll(strat_defs);
			}
		} catch (Exception e) {
			log.log(Level.SEVERE,
							"Can not instantiate VHosts repository for class: " +
							strategy_class, e);
		}
		return props;
	}

	@Override
	public void nodeConnected(String node) {
		log.fine("Nodes connected: " + node);
		String jid = getName() + "@" + node;
		strategy.nodeConnected(jid);
		addTrusted(jid);
	}

	@Override
	public void nodeDisconnected(String node) {
		log.fine("Nodes disconnected: " + node);
		String jid = getName() + "@" + node;
		strategy.nodeDisconnected(jid);
		delTrusted(jid);
	}

	protected String getFirstClusterNode(String userId) {
		String cluster_node = null;
		List<String> nodes = strategy.getNodesForJid(userId);
		for (String node: nodes) {
			if (!node.equals(getComponentId())) {
				cluster_node = node;
				break;
			}
		}
		return cluster_node;
	}

	@Override
	public void handleLogin(String userName, XMPPResourceConnection conn) {
		super.handleLogin(userName, conn);
		if (!conn.isAnonymous()) {
			String userId = JIDUtils.getNodeID(userName, conn.getDomain());
			String cluster_node = getFirstClusterNode(userId);
			if (cluster_node != null) {
				try {
					String xmpp_sessionId = conn.getSessionId();
					String token = conn.getAuthenticationToken(xmpp_sessionId);
					String connectionId = conn.getConnectionId();
					String resource = conn.getResource();
					int priority = conn.getPriority();
					long authTime = conn.getAuthTime();
					log.finest("Sending user: " + userId
						+ " session, resource: " + resource
						+ ", xmpp_sessionId: " + xmpp_sessionId
						+ ", connectionId: " + connectionId);
					conn.setConnectionStatus(ConnectionStatus.ON_HOLD);
					Map<String, String> params = new LinkedHashMap<String, String>();
					params.put(USER_ID, userId);
					params.put(XMPP_SESSION_ID, xmpp_sessionId);
					//params.put(RESOURCE, resource);
					params.put(CONNECTION_ID, connectionId);
					params.put(PRIORITY, "" + priority);
					params.put(TOKEN, token);
					params.put(AUTH_TIME, ""+authTime);
					Element check_session_el = ClusterElement.createClusterMethodCall(
						getComponentId(), cluster_node, StanzaType.set,
						ClusterMethods.SESSION_TRANSFER.toString(), params).getClusterElement();
					fastAddOutPacket(new Packet(check_session_el));
				} catch (Exception e) {
					log.log(Level.WARNING, "Problem with session transfer process, ", e);
				}
			}
		}
	}

	@Override
	public void release() {
		delayedTasks.cancel();
		super.release();
	}

	@Override
	public void start() {
		super.start();
		delayedTasks = new Timer("SM Cluster Delayed Tasks", true);
	}

	@Override
	public void setClusterController(ClusterController cl_controller) {
	}

}
