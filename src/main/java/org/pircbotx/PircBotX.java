/**
 * Copyright (C) 2010-2014 Leon Blakey <lord.quackstar at gmail.com>
 *
 * This file is part of PircBotX.
 *
 * PircBotX is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * PircBotX is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * PircBotX. If not, see <http://www.gnu.org/licenses/>.
 */
package org.pircbotx;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.pircbotx.dcc.DccHandler;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.*;
import org.pircbotx.output.OutputCAP;
import org.pircbotx.output.OutputDCC;
import org.pircbotx.output.OutputIRC;
import org.pircbotx.output.OutputRaw;
import org.pircbotx.snapshot.UserChannelDaoSnapshot;

/**
 * PircBotX is a Java framework for writing IRC bots quickly and easily.
 * <p>
 * It provides an event-driven architecture to handle common IRC events, flood
 * protection, DCC support, ident support, and more. The comprehensive logfile
 * format is suitable for use with pisg to generate channel statistics.
 * <p>
 * Methods of the PircBotX class can be called to send events to the IRC server
 * that it connects to. For example, calling the sendMessage method will send a
 * message to a channel or user on the IRC server. Multiple servers can be
 * supported using multiple instances of PircBotX.
 * <p>
 * To perform an action when the PircBotX receives a normal message from the IRC
 * server, you would listen for the MessageEvent in your listener (see
 * {@link ListenerAdapter}). Many other events are dispatched as well for other
 * incoming lines
 *
 * @author Origionally by:
 * <a href="http://www.jibble.org/">Paul James Mutton</a> for <a
 * href="http://www.jibble.org/pircbot.php">PircBot</a>
 * <p>
 * Forked and Maintained by Leon Blakey in <a
 * href="http://pircbotx.googlecode.com">PircBotX</a>
 */
@Slf4j
public class PircBotX implements Comparable<PircBotX>, Closeable {
	/**
	 * The definitive version number of this release of PircBotX.
	 */
	//THIS LINE IS AUTOGENERATED, DO NOT EDIT
	public static final String VERSION = "2.1-SNAPSHOT";
	protected static final AtomicInteger BOT_COUNT = new AtomicInteger();
	/**
	 * Unique number for this bot
	 */
	@Getter
	protected final int botId;
	//Utility objects
	/**
	 * Configuration used for this bot
	 */
	@Getter
	protected final Configuration configuration;
	@Getter
	protected final InputParser inputParser;
	/**
	 * User-Channel mapper
	 */
	@Getter
	protected final UserChannelDao<User, Channel> userChannelDao;
	@Getter
	protected final DccHandler dccHandler;
	protected final ServerInfo serverInfo;
	//Connection stuff.
	@Getter(AccessLevel.PROTECTED)
	protected Socket socket;
	protected BufferedReader inputReader;
	protected OutputStreamWriter outputWriter;
	protected final OutputRaw outputRaw;
	protected final OutputIRC outputIRC;
	protected final OutputCAP outputCAP;
	protected final OutputDCC outputDCC;
	/**
	 * Enabled CAP features
	 */
	@Getter
	protected List<String> enabledCapabilities = new ArrayList<String>();
	protected String nick = "";
	protected boolean loggedIn = false;
	protected Thread shutdownHook;
	protected volatile boolean reconnectStopped = false;
	protected ImmutableMap<String, String> reconnectChannels;
	private State state = State.INIT;
	protected final Object stateLock = new Object();
	protected Exception disconnectException;
	@Getter
	protected String serverHostname;
	@Getter
	protected int serverPort;
	/**
	 *
	 */
	@Getter
	@Setter(AccessLevel.PROTECTED)
	protected boolean nickservIdentified = false;
	private int connectAttempts = 0;
	private final AtomicInteger attemptCounter = new AtomicInteger();

	/**
	 * Constructs a PircBotX with the provided configuration.
	 *
	 * @param configuration Fully built Configuration
	 */
	@SuppressWarnings("unchecked")
	public PircBotX(@NonNull Configuration configuration) {
		botId = BOT_COUNT.getAndIncrement();
		this.configuration = configuration;

		//Pre-insert an initial User representing the bot itself
		this.userChannelDao = configuration.getBotFactory().createUserChannelDao(this);
		getUserChannelDao().addUserToPrivate(new User(new UserHostmask(this, configuration.getName(), configuration.getLogin(), null)));

		this.serverInfo = configuration.getBotFactory().createServerInfo(this);
		this.outputRaw = configuration.getBotFactory().createOutputRaw(this);
		this.outputIRC = configuration.getBotFactory().createOutputIRC(this);
		this.outputCAP = configuration.getBotFactory().createOutputCAP(this);
		this.outputDCC = configuration.getBotFactory().createOutputDCC(this);
		this.dccHandler = configuration.getBotFactory().createDccHandler(this);
		this.inputParser = configuration.getBotFactory().createInputParser(this);
	}

	/**
	 * Start the bot by connecting to the server. If
	 * {@link Configuration#isAutoReconnect()} is true this will continuously
	 * reconnect to the server until {@link #stopBotReconnect() } is called or
	 * an exception is thrown from connecting
	 *
	 * @throws IOException if it was not possible to connect to the server.
	 * @throws IrcException
	 */
	public void startBot() throws IOException, IrcException {
		reconnectStopped = false;
		do {
			//Connection attempt
			try {
				attemptCounter.incrementAndGet();
				connect();
			} catch (Exception e) {
				log.error("Exception encountered during connect", e);
				if (state != State.DISCONNECTED)
					shutdown();
				if (configuration.isAutoReconnect() && !reconnectStopped) {
					//redirect exception
					Utils.dispatchEvent(this, new ConnectFailedEvent(this, e));
				} else
					throw new RuntimeException("Exception encountered during connect", e);
			}

			//Wait
			if (configuration.getAutoReconnectDelay() > 0 && connectAttempts == configuration.getAutoReconnectAttempts())
				try {
					log.debug("Pausing for " + configuration.getAutoReconnectDelay() + " milliseconds before connecting again");
					Thread.sleep(configuration.getAutoReconnectDelay());
				} catch (InterruptedException e) {
					log.debug("could not finish", e);
					throw new RuntimeException("Could not finish pausing starting", e);
				}
		} while (configuration.isAutoReconnect() && !reconnectStopped && connectAttempts < configuration.getAutoReconnectAttempts());

		if (configuration.isAutoReconnect() && !reconnectStopped && connectAttempts == configuration.getAutoReconnectAttempts()) {
			log.error("Maxed out connection attempts");
		}
	}

	/**
	 * Stops the bot from reconnecting constantly to the server in the future.
	 */
	public void stopBotReconnect() {
		reconnectStopped = true;
	}

	/**
	 * Attempt to connect to the specified IRC server using the supplied port
	 * number, password, and socketFactory. On success a {@link ConnectEvent}
	 * will be dispatched
	 *
	 * @throws IOException if it was not possible to connect to the server.
	 * @throws IrcException if the server would not let us join it.
	 */
	protected void connect() throws IOException, IrcException {
		synchronized (stateLock) {
			//Server id
			Utils.addBotToMDC(this);
			if (isConnected())
				throw new IrcException(IrcException.Reason.AlreadyConnected, "Must disconnect from server before connecting again");
			if (getState() == State.CONNECTED)
				throw new RuntimeException("Bot is not connected but state is State.CONNECTED. This shouldn't happen");
			if (configuration.isIdentServerEnabled() && IdentServer.getServer() == null)
				throw new RuntimeException("UseIdentServer is enabled but no IdentServer has been started");

			//Reset capabilities
			enabledCapabilities = new ArrayList<String>();

			// Connect to the server by DNS server
			Exception lastException = null;
			ReconnectLoop:
			do {
				connectAttempts++;

				int serverEntryCounter = 0;
				for (Configuration.ServerEntry curServerEntry : configuration.getServers()) {
					serverEntryCounter++;
					serverHostname = curServerEntry.getHostname();
					//Hostname and port
					Utils.addBotToMDC(this);
					log.info("---Starting Connect attempt {}/{}", connectAttempts, configuration.getAutoReconnectAttempts() + "---");

					int serverAddressCounter = 0;
					InetAddress[] serverAddresses = InetAddress.getAllByName(serverHostname);
					for (InetAddress curAddress : serverAddresses) {
						serverAddressCounter++;
						String debug = Utils.format("[{}/{} address left from {}, {}/{} hostnames left] ",
								String.valueOf(serverAddresses.length - serverAddressCounter),
								String.valueOf(serverAddresses.length),
								serverHostname,
								String.valueOf(configuration.getServers().size() - serverEntryCounter),
								String.valueOf(configuration.getServers().size())
						);
						log.debug("{}Atempting to connect to {} on port {}", debug, curAddress, curServerEntry.getPort());
						try {
							socket = configuration.getSocketFactory().createSocket(curAddress, curServerEntry.getPort(), configuration.getLocalAddress(), 0);

							//No exception, assume successful
							serverPort = curServerEntry.getPort();
							break ReconnectLoop;
						} catch (Exception e) {
							lastException = e;
							log.debug("{}Failed to connect to {} on port {}",
									debug,
									curAddress,
									curServerEntry.getPort()/*,
							 e*/);
							configuration.getListenerManager().dispatchEvent(new ConnectAttemptFailedEvent(this,
									curAddress,
									curServerEntry.getPort(),
									configuration.getLocalAddress(),
									serverAddresses.length,
									e));
						}
					}
				}

			} while (configuration.isAutoReconnect() && configuration.getAutoReconnectAttempts() - connectAttempts <= 0);

			//Make sure were connected
			if (socket == null || (socket != null && !socket.isConnected())) {
				throw new IOException("Unable to connect to the IRC servers " + configuration.getServers()
						+ " (last connection attempt exception attached)", lastException);
			}
			state = State.CONNECTED;
			socket.setSoTimeout(configuration.getSocketTimeout());
			log.info("Connected to server.");

			changeSocket(socket);
		}

		configuration.getListenerManager().dispatchEvent(new SocketConnectEvent(this));

		if (configuration.isIdentServerEnabled())
			IdentServer.getServer().addIdentEntry(socket.getInetAddress(), socket.getPort(), socket.getLocalPort(), configuration.getLogin());

		if (configuration.isCapEnabled())
			// Attempt to initiate a CAP transaction.
			sendCAP().getSupported();

		// Attempt to join the server.
		if (configuration.isWebIrcEnabled())
			sendRaw().rawLineNow("WEBIRC " + configuration.getWebIrcPassword()
					+ " " + configuration.getWebIrcUsername()
					+ " " + configuration.getWebIrcHostname()
					+ " " + configuration.getWebIrcAddress().getHostAddress());
		if (StringUtils.isNotBlank(configuration.getServerPassword()))
			sendRaw().rawLineNow("PASS " + configuration.getServerPassword());

		sendRaw().rawLineNow("NICK " + configuration.getName());
		sendRaw().rawLineNow("USER " + configuration.getLogin() + " 8 * :" + configuration.getRealName());

		//Pre-insert an initial User representing the bot itself
		if (!getUserChannelDao().containsUser(configuration.getName()))
			getUserChannelDao().addUserToPrivate(new User(new UserHostmask(this, configuration.getName(), configuration.getLogin(), null)));

		//Start input to start accepting lines
		startLineProcessing();
	}

	protected void changeSocket(Socket socket) throws IOException {
		this.socket = socket;
		this.inputReader = new BufferedReader(new InputStreamReader(socket.getInputStream(), configuration.getEncoding()));
		this.outputWriter = new OutputStreamWriter(socket.getOutputStream(), configuration.getEncoding());
	}

	protected void startLineProcessing() {
		while (true) {
			//Get line from the server
			String line;
			try {
				line = inputReader.readLine();
			} catch (InterruptedIOException iioe) {
				// This will happen if we haven't received anything from the server for a while.
				// So we shall send it a ping to check that we are still connected.
				sendRaw().rawLine("PING " + (System.currentTimeMillis() / 1000));
				// Now we go back to listening for stuff from the server...
				continue;
			} catch (Exception e) {
				if (Thread.interrupted()) {
					System.out.println("--- PircBotX interrupted during read, aborting reconnect loop and shutting down ---");
					stopBotReconnect();
					break;
				} else if (socket.isClosed()) {
					log.info("Socket is closed, stopping read loop and shutting down");
					break;
				} else {
					disconnectException = e;
					//Something is wrong. Assume its bad and begin disconnect
					log.error("Exception encountered when reading next line from server", e);
					line = null;
				}
			}

			if (Thread.interrupted()) {
				System.out.println("--- PircBotX interrupted during read, aborting reconnect loop and shutting down ---");
				stopBotReconnect();
				break;
			}

			//End the loop if the line is null
			if (line == null)
				break;

			//Start acting the line
			try {
				inputParser.handleLine(line);
			} catch (Exception e) {
				//Exception in client code. Just log and continue
				log.error("Exception encountered when parsing line " + line, e);
			}

			if (Thread.interrupted()) {
				System.out.println("--- PircBotX interrupted during parsing, aborting reconnect loop and shutting down ---");
				stopBotReconnect();
				break;
			}
		}

		//Now that the socket is definitely closed call event, log, and kill the OutputThread
		shutdown();
	}

	/**
	 * Actually sends the raw line to the server. This method is NOT
	 * SYNCHRONIZED since it's only called from methods that handle locking
	 *
	 * @param line
	 * @throws java.io.IOException
	 */
	protected void sendRawLineToServer(String line) throws IOException {
		if (line.length() > configuration.getMaxLineLength() - 2)
			line = line.substring(0, configuration.getMaxLineLength() - 2);
		outputWriter.write(line + "\r\n");
		outputWriter.flush();

		List<String> lineParts = Utils.tokenizeLine(line);
		getConfiguration().getListenerManager().dispatchEvent(new OutputEvent(this, line, lineParts));
	}

	protected void loggedIn(String nick) {
		this.loggedIn = true;
		setNick(nick);

		if (configuration.isShutdownHookEnabled())
			Runtime.getRuntime().addShutdownHook(shutdownHook = new PircBotX.BotShutdownHook(this));
	}

	public OutputRaw sendRaw() {
		return outputRaw;
	}

	public OutputIRC sendIRC() {
		return outputIRC;
	}
	
	public OutputIRC send() {
		return outputIRC;
	}

	public OutputCAP sendCAP() {
		return outputCAP;
	}

	public OutputDCC sendDCC() {
		return outputDCC;
	}

	/**
	 * Sets the internal nick of the bot. This is only to be called by the
	 * PircBotX class in response to notification of nick changes that apply to
	 * us.
	 *
	 * @param nick The new nick.
	 */
	protected void setNick(String nick) {
		this.nick = nick;
	}

	/**
	 * Returns the current nick of the bot. Note that if you have just changed
	 * your nick, this method will still return the old nick until confirmation
	 * of the nick change is received from the server.
	 *
	 * @since PircBot 1.0.0
	 *
	 * @return The current nick of the bot.
	 */
	public String getNick() {
		return nick;
	}

	/**
	 * Returns whether or not the PircBotX is currently connected to a server.
	 * The result of this method should only act as a rough guide, as the result
	 * may not be valid by the time you act upon it.
	 *
	 * @return True if and only if the PircBotX is currently connected to a
	 * server.
	 */
	@Synchronized("stateLock")
	public boolean isConnected() {
		return socket != null && !socket.isClosed();
	}

	/**
	 * Returns a String representation of this object. You may find this useful
	 * for debugging purposes, particularly if you are using more than one
	 * PircBotX instance to achieve multiple server connectivity. The format of
	 * this String may change between different versions of PircBotX but is
	 * currently something of the form 	 <code>
	 *   Version{PircBotX x.y.z Java IRC Bot - www.jibble.org}
	 *   Connected{true}
	 *   Server{irc.dal.net}
	 *   Port{6667}
	 *   Password{}
	 * </code>
	 *
	 * @since PircBot 0.9.10
	 *
	 * @return a String representation of this object.
	 */
	@Override
	public String toString() {
		return "Version{" + configuration.getVersion() + "}"
				+ " Connected{" + isConnected() + "}"
				+ " Server{" + getServerHostname() + "}"
				+ " Port{" + getServerPort() + "}"
				+ " Password{" + configuration.getServerPassword() + "}";
	}

	/**
	 * Gets the bots own user object.
	 *
	 * @return The user object representing this bot
	 */
	public User getUserBot() {
		return userChannelDao.getUser(getNick());
	}

	/**
	 * @return the serverInfo
	 */
	public ServerInfo getServerInfo() {
		return serverInfo;
	}

	public InetAddress getLocalAddress() {
		return socket.getLocalAddress();
	}
	
	public int getConnectionId() {
		return attemptCounter.get();
	}

	/**
	 * Get the auto reconnect channels and clear local copy
	 *
	 * @return
	 */
	protected ImmutableMap<String, String> reconnectChannels() {
		ImmutableMap<String, String> reconnectChannelsLocal = reconnectChannels;
		reconnectChannels = null;
		return reconnectChannelsLocal;
	}

	/**
	 * Close socket, causes read loop to terminate and shutdown PircBotX 
	 */
	public void close() {
		try {
			socket.close();
		} catch (Exception e) {
			log.error("Can't close socket", e);
		}
	}

	/**
	 * Fully shutdown the bot and all internal resources. This will close the
	 * connections to the server, kill background threads, clear server specific
	 * state, and dispatch a DisconnectedEvent
	 */
	private void shutdown() {
		UserChannelDaoSnapshot daoSnapshot;
		synchronized (stateLock) {
			log.debug("---PircBotX shutdown started---");
			if (state == State.DISCONNECTED)
				throw new RuntimeException("Cannot call shutdown twice");
			state = State.DISCONNECTED;

			if (configuration.isIdentServerEnabled())
				IdentServer.getServer().removeIdentEntry(socket.getInetAddress(), socket.getPort(), socket.getLocalPort(), configuration.getLogin());

			try {
				socket.close();
			} catch (Exception e) {
				log.error("Can't close socket", e);
			}

			//Close the socket from here and let the threads die
			if (socket != null && !socket.isClosed())
				try {
					socket.close();
				} catch (Exception e) {
					log.error("Cannot close socket", e);
				}

			//Cache channels for possible next reconnect
			ImmutableMap.Builder<String, String> reconnectChannelsBuilder = ImmutableMap.builder();
			for (Channel curChannel : userChannelDao.getAllChannels()) {
				String key = (curChannel.getChannelKey() == null) ? "" : curChannel.getChannelKey();
				reconnectChannelsBuilder.put(curChannel.getName(), key);
			}
			reconnectChannels = reconnectChannelsBuilder.build();

			//Clear relevant variables of information
			loggedIn = false;
			daoSnapshot = userChannelDao.createSnapshot();
			userChannelDao.close();
			inputParser.close();
			dccHandler.close();
		}

		//Dispatch event
		configuration.getListenerManager().dispatchEvent(new DisconnectEvent(this, daoSnapshot, disconnectException));
		disconnectException = null;
		log.debug("Disconnected.");

		//Shutdown listener manager
		configuration.getListenerManager().shutdown(this);
	}

	/**
	 * Compare {@link #getBotId() bot id's}. This is useful for sorting lists of
	 * Channel objects.
	 *
	 * @param other Other channel to compare to
	 * @return the result of calling compareToIgnoreCase on channel names.
	 */
	public int compareTo(PircBotX other) {
		return Ints.compare(getBotId(), other.getBotId());
	}

	/**
	 * @return the state
	 */
	@Synchronized("stateLock")
	public State getState() {
		return state;
	}

	protected static class BotShutdownHook extends Thread {
		protected final WeakReference<PircBotX> thisBotRef;

		public BotShutdownHook(PircBotX bot) {
			this.thisBotRef = new WeakReference<PircBotX>(bot);
			setName("bot" + BOT_COUNT + "-shutdownhook");
		}

		@Override
		public void run() {
			PircBotX thisBot = thisBotRef.get();
			if (thisBot != null && thisBot.getState() != PircBotX.State.DISCONNECTED) {
				thisBot.stopBotReconnect();
				thisBot.sendIRC().quitServer();
				try {
					if (thisBot.isConnected())
						thisBot.socket.close();
				} catch (IOException ex) {
					log.debug("Unabloe to forcibly close socket", ex);
				}
			}
		}
	}

	public static enum State {
		INIT,
		CONNECTED,
		DISCONNECTED
	}
}
