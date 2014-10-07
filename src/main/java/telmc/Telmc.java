package telmc;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import sun.security.action.GetLongAction;

import com.google.common.io.OutputSupplier;
import com.google.common.net.InetAddresses;

import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommandSender;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.minecart.MinecartEvent;
import cpw.mods.fml.common.FMLLog;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@Mod(modid = Telmc.MODID, name = Telmc.NAME, version = Telmc.VERSION)
public class Telmc
{
    public static final String MODID = "telmc";
    public static final String NAME = "TelMC";
    public static final String VERSION = "0.3";
    
    private String address;
    private int port;
    private String userName;
    private String password;

    private Thread listenerThread;

	@NetworkCheckHandler
	public boolean netCheckHandler(Map<String, String> mods, Side side)
	{
		return true;
	}

	@SideOnly(Side.SERVER)
    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
    	Configuration configuration = new Configuration(event.getSuggestedConfigurationFile());

    	configuration.load();

    	address = configuration.getString("address", "listener", "0.0.0.0", "Telnet server binding address");
    	port = configuration.getInt("port", "listener", 23023, 0, 65535, "Telnet server binding port");
    	userName = configuration.getString("name", "login", "admin", "Login user name");
    	password = configuration.getString("password", "login", "admin", "Login user password");
    	
    	configuration.save();
    }

	@SideOnly(Side.SERVER)
    @EventHandler
    public void init(FMLInitializationEvent event) throws IOException
    {
		Listener listener = new Listener(address, port, userName, password);
    	listenerThread = new Thread(listener, "TELNET Listener");
    	listenerThread.start();
    }
}

@SideOnly(Side.SERVER)
class Listener implements Runnable {
	private ServerSocket socket;
	private String userName, password;
	private ArrayList<Session> sessions = new ArrayList<Session>();
	
	Listener(String address, int port, String userName, String password) throws IOException {
		this.userName = userName;
		this.password = password;
		
		socket = new ServerSocket();
		socket.bind(new InetSocketAddress(address, port));
	}
	
	@EventHandler
	public void serverChatEvent(ServerChatEvent e) {
		System.out.println("Listener" + e);
	}
	
	public void run() {
		System.out.println("TELNET Server Started on " + socket.getLocalSocketAddress());
		while (true) {
			try {
				System.out.println("Waiting Client");
				Socket client = socket.accept();
				System.out.println(client.getRemoteSocketAddress());
				Thread sessionThread = new Thread(new Session(client, sessions, userName, password), "TELNET Session");
				sessionThread.start();
			} catch (IOException e) {
				return;
			}
		}
	}
}

@SideOnly(Side.SERVER)
class CommandSenderWrapper implements ICommandSender {
	private ICommandSender wrapped;
	private Session session;

	CommandSenderWrapper(Session session, ICommandSender wrapped) {
		this.session = session;
		this.wrapped = wrapped;
	}

	@Override
	public void addChatMessage(IChatComponent arg0) {
		wrapped.addChatMessage(arg0);
		try {
			session.write(arg0.getUnformattedTextForChat());
		} catch (IOException e) {
			System.err.println(e);
		}
	}

	@Override
	public boolean canCommandSenderUseCommand(int arg0, String arg1) {
		return wrapped.canCommandSenderUseCommand(arg0, arg1);
	}

	@Override
	public IChatComponent func_145748_c_() {
		return wrapped.func_145748_c_();
	}

	@Override
	public String getCommandSenderName() {
		return wrapped.getCommandSenderName();
	}

	@Override
	public World getEntityWorld() {
		return wrapped.getEntityWorld();
	}

	@Override
	public ChunkCoordinates getPlayerCoordinates() {
		return wrapped.getPlayerCoordinates();
	}
}

@SideOnly(Side.SERVER)
class Session implements Runnable {
    private static final int BUFSIZE = 1024;
	private Socket socket;
	private String userName, password;
	private ArrayList<Session> sessions;
	
	Session(Socket socket, ArrayList<Session> sessions, String userName, String password) throws IOException {
		this.socket = socket;
		this.sessions = sessions;
		this.userName = userName;
		this.password = password;

		System.out.println("TELNET Session Started");
	}
	
	public void run() {
		try {
			System.out.println("TELNET Session Started");
			
			InputStream in = socket.getInputStream();
			PrintWriter out = new PrintWriter(socket.getOutputStream(), false);
			
			out.write("Welcome!\r\n");
			out.write("Login Name: ");
			out.flush();
			
			char[] buffer = new char[1024];
			int bufferPointer = 0;
			
			MinecraftServer server = MinecraftServer.getServer();
			
			int mode = 0;
			
			while (!socket.isInputShutdown() && !socket.isOutputShutdown()) {
				char c = (char)in.read();
				if (c > 0 && c < 0x80) {
					 if (c == 13 || c == 10) {
						 char[] tmp = new char[bufferPointer];
						 System.arraycopy(buffer, 0, tmp, 0, bufferPointer);
						String command = new String(tmp);
						bufferPointer = 0;
						
						if (mode == 0) {	// Name
							if (command.equals(userName)) {
								mode = 1;
								out.write("\r\nPassword: ");
							} else {
								out.write("\r\nLogin Name: ");
							}
						} else if (mode == 1) {	// Password
							if (command.equals(password)) {
								mode = -1;
								
								sessions.add(this);
								
								executeCommand("say " + userName + " logged in.");
								out.write("\r\nWelcome!\r\n");
								out.write("telmc> ");
							} else {
								out.write("\r\nPassword: ");
							}
						} else if (mode == -1) {
							if (command.equals("quit")) {
								break;
							} else {
								out.write("\r\n");
								out.flush();
								executeCommand(command);
							}
							out.write("telmc> ");
						}
					} else {
						buffer[bufferPointer++] = c;
						out.write(c);
					}
					out.flush();
				} else if (c == 0xff) {
					char cmd = (char)in.read();
					char opt = (char)in.read();
				}
			}
			
			sessions.remove(this);
			socket.close();
		} catch (IOException e) {
		}

		System.out.println("TELNET Session Closed");
	}
	
	private void executeCommand(String command) {
		MinecraftServer server = MinecraftServer.getServer();
		server.getCommandManager().executeCommand(new CommandSenderWrapper(this, server), command);
	}
	
	public void write(String str) throws IOException {
		PrintWriter out = new PrintWriter(socket.getOutputStream(), false);
		out.write(str);
		out.write("\r\n");
		out.flush();
	}
}
