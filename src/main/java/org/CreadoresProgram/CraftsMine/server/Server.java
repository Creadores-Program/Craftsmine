package org.CreadoresProgram.CraftsMine.server;
import lombok.Getter;
import lombok.Setter;
import org.yaml.snakeyaml.Yaml;
import org.CreadoresProgram.CraftsMine.utils.Logger;
import org.CreadoresProgram.CraftsMine.utils.TextFormat;
import org.CreadoresProgram.CraftsMine.utils.NbtBlockDefinitionRegistry;
import java.io.*;
import org.apache.commons.io.output.TeeOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.net.URL;
import javax.swing.Timer;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;
import org.cloudburstmc.nbt.NBTInputStream;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.cloudburstmc.nbt.NbtUtils;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
//1.7
import org.cloudburstmc.protocol.bedrock.codec.v291.Bedrock_v291;
//1.8
import org.cloudburstmc.protocol.bedrock.codec.v313.Bedrock_v313;
//1.9
import org.cloudburstmc.protocol.bedrock.codec.v332.Bedrock_v332;
//1.10
import org.cloudburstmc.protocol.bedrock.codec.v340.Bedrock_v340;
import org.CreadoresProgram.CraftsMine.player.Player;
import org.CreadoresProgram.CraftsMine.options.Config;
import org.CreadoresProgram.CraftsMine.utils.FileManager;
import org.CreadoresProgram.CraftsMine.network.RaknetInterfaz;
import org.CreadoresProgram.CraftsMine.utils.QueryTask;
public class Server{
  @Getter
  private static Server instance = null;
  @Getter
  private final Map<String, Player> bedrockPlayers = new ConcurrentHashMap<>();
  @Getter
  private BedrockCodec bedrockPacketCodec = null;
  @Getter
  private final Path dataPath;
  @Getter
  private Config config;
  @Getter
  private String defaultSkinData;
  @Getter
  private String defaultSkinGeomety;
  @Getter
  private Logger logger;
  @Getter
  private RaknetInterfaz rakNetServ;

  @Getter
  private NbtBlockDefinitionRegistry blockDefinitions;

  private ThreadTik ticker;
  private CommandExecutor commandDetect;

  @Getter
  private String softwareVersion = "0.3.1";
  @Getter
  private String softwareName = "CraftsMine";
  @Getter
  private String softwareVersionName = "Prototype";
  @Getter
  private String softwareAuthor = "Creadores Program©";
  
  public Server(String dataPath){
    instance = this;
    this.logger = new Logger(TextFormat.GOLD.getAnsiCode()+"Crafts"+TextFormat.MATERIAL_GOLD.getAnsiCode()+"Mine");
    this.getLogger().info(softwareName+" v"+softwareVersion+" "+softwareVersionName+" Starting...");
    this.getLogger().info("Loading Configs...");
    this.dataPath = Paths.get(dataPath);
    if(!initConfig()){
      this.getLogger().emergency("Config file not found! Terminating...");
      System.exit(1);
    }
    if(this.getConfig().isConsoleLog()){
      try{
        FileOutputStream fos = new FileOutputStream("console.log", true);
            TeeOutputStream tos = new TeeOutputStream(System.out, fos);
            PrintStream ps = new PrintStream(tos, true);
            System.setOut(ps);
            System.setErr(ps);
      }catch(IOException ex){
        this.getLogger().critical("Critical error: ", ex);
        System.exit(1);
      }
    }
    if(this.config.isGcCollectionAuto()){
      int tikGc = 1800000;
      if(this.config.getGcCollectionTime() < 1){
        this.getLogger().warn("The value of gcCollectionTime is invalid!");
        this.getLogger().info("Using the default time...");
      }else{
        tikGc = (int) this.config.getGcCollectionTime();
      }
      Timer timer = new Timer(tikGc, new ActionListener(){
        public void actionPerformed(ActionEvent e){
          System.gc();
        }
      });
      timer.setRepeats(true);
      timer.start();
    }
    if(this.config.isSynchServer()){
      int tikQuery = 15000;
      if(this.config.getTimeSynchServer() < 1){
        this.getLogger().warn("The value of timeSynchServer is invalid!");
        this.getLogger().info("Using the default time...");
      }else{
        tikQuery = (int) this.config.getTimeSynchServer();
      }
      Timer timerQ = new Timer(tikQuery, new QueryTask());
      timerQ.setRepeats(true);
      timerQ.start();
    }
    switch(this.config.getVersionBedrockServer()){
      case "1.8":
        this.bedrockPacketCodec = Bedrock_v313.CODEC;
        break;
      case "1.9":
        this.bedrockPacketCodec = Bedrock_v332.CODEC;
        break;
      case "1.10":
        this.bedrockPacketCodec = Bedrock_v340.CODEC;
        break;
      case "1.7":
        this.bedrockPacketCodec = Bedrock_v291.CODEC;
        break;
      default:
        this.bedrockPacketCodec = Bedrock_v291.CODEC;
        this.getLogger().warn("Version Not Found Using 1.7!");
        break;
    }
    this.getLogger().info("Loading Block Definitions...");
    loadBlockDefinitions();
    this.getLogger().info("Loading Default Skin...");
    loadDefaultSkin();
    this.getLogger().info("Starting Server...");
    startServer();
  }
  private boolean initConfig() {
        File configFile = new File(dataPath.toFile(), "config.yml");
        if (!configFile.exists()) {
            try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (inputStream == null) {
                    return false;
                }
                Files.createDirectories(configFile.getParentFile().toPath());
                Files.copy(inputStream, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                return false;
            }
        }
        try {
            this.config = new Yaml().loadAs(Files.newBufferedReader(configFile.toPath()), Config.class);
        } catch (IOException e) {
            return false;
        }
        return true;
    }
  private void loadDefaultSkin() {
        try {
            this.defaultSkinData = FileManager.getFileContents(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("skin/skin_data.txt")));
            this.defaultSkinGeomety = FileManager.getFileContents(Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("skin/skin_geometry.json")));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
  private void loadBlockDefinitions(){
    try(InputStream inputStream = getClass().getClassLoader().getResourceAsStream("block_palette.nbt")){
      assert inputStream != null;
      try(NBTInputStream nbtInputStream = NbtUtils.createGZIPReader(inputStream)){
        Object object = nbtInputStream.readTag();
        if(object instanceof NbtMap){
          NbtMap blocksTag = (NbtMap) object;
          blockDefinitions = new NbtBlockDefinitionRegistry(blocksTag.getList("blocks", NbtType.COMPOUND));
          // Initialize runtime palette manager (loads per-version palettes if present and
          // populates Item.BLOCKS_AVIABLE mappings where possible).
          try {
            org.CreadoresProgram.CraftsMine.utils.RuntimePaletteManager.init();
          } catch (Throwable t) {
            this.getLogger().warn("RuntimePaletteManager init failed: " + t);
          }
        }
      }
    } catch(IOException e){
      throw new RuntimeException("Failed to load block definitions", e);
    }
  }
  private void startServer(){
    rakNetServ = new RaknetInterfaz(this, config.getBindAddress(), config.getPort());
    rakNetServ.setMOTD(config.getMotd(), config.getSubmotd());
    this.ticker = new ThreadTik(this);
    this.ticker.start();
    this.commandDetect = new CommandExecutor(this);
    this.commandDetect.start();
    this.getLogger().info("Done!");
    System.gc();
  }
  public void stopServer(){
    this.getLogger().info("Turning off Proxy...");
    for(Player player : this.bedrockPlayers.values()){
      player.disconnect(this.getConfig().getShutdownMessage());
    }
    this.getLogger().info("Turning off Threads...");
    this.ticker.isOn = false;
    this.commandDetect.isOn = false;
    this.getLogger().info("Turning off RakNet Server by Nukkit MagmaBlock Edition...");
    rakNetServ.shutdown();
    this.getLogger().info("CraftsMine Offline!");
    System.exit(0);
  }
  public class CommandExecutor extends Thread{
    private Server serv;
    public boolean isOn = true;
    public CommandExecutor(Server server){
      this.serv = server;
    }
    @Override
    public void run(){
      BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
      while(this.isOn){
        String cmd = null;
        try{
        cmd = reader.readLine();
        }catch(Exception ex){
          
        }
        if(cmd == null || cmd == ""){
          return;
        }
        switch(cmd.toLowerCase()){
          case "stop":
          case "end":
            this.serv.stopServer();
            break;
          case "ver":
          case "version":
          case "about":
            this.serv.getLogger().info(TextFormat.AQUA.getAnsiCode()+"--SOFTWARE INFO--\n"+TextFormat.GREEN.getAnsiCode()+" "+this.serv.getSoftwareName()+" V"+this.serv.getSoftwareVersion()+" "+this.serv.getSoftwareVersionName()+" By "+this.serv.getSoftwareAuthor()+"\n"+TextFormat.GOLD.getAnsiCode()+"Crossplay Software MCPE 0.15.X/Craftsman-MCBE/Minecraft Bedrock(1.7|1.8|1.9|1.10)\n"+TextFormat.AQUA.getAnsiCode()+"-----------------");
            break;
          case "playerlist":
            this.serv.getLogger().info("Players: ("+this.serv.getBedrockPlayers().size()+"/"+this.serv.getConfig().getMaxplayers()+"):\n");
            for(Player playe : this.serv.getBedrockPlayers().values()){
              if(playe.getCraftsmanUsername() == null){
                continue;
              }
              this.serv.getLogger().info(playe.getCraftsmanUsername());
            }
            break;
          case "help":
          case "?":
            this.serv.getLogger().info(TextFormat.RED.getAnsiCode()+"'help': get help for commands in the console");
            this.serv.getLogger().info(TextFormat.YELLOW.getAnsiCode()+"'playerlist' get a list of online players");
            this.serv.getLogger().info(TextFormat.DARK_RED.getAnsiCode()+"'stop' stop the server");
            this.serv.getLogger().info(TextFormat.GOLD.getAnsiCode()+"'version' server information");
            break;
          default:
            this.serv.getLogger().info(TextFormat.RED.getAnsiCode()+"Use 'help' to help!");
            break;
        }
      }
      try{
        reader.close();
      }catch(Exception ex){
          
      }
    }
  }
  public class ThreadTik extends Thread{
    private Server serv;
    public boolean isOn = true;
      public ThreadTik(Server server){
        this.serv = server;
        setDaemon(true);
      }
      @Override
      public void run(){
        long time;
        while (this.isOn){
          time = System.currentTimeMillis();
          serv.getRakNetServ().onTick();
          time = System.currentTimeMillis() - time;
          if(time >= 50){
            continue;
          }else{
            try{
              Thread.sleep(50 - time);
            } catch(InterruptedException ex){
              return;
            }
          }
        }
      }
  }
}
