package Creadores.Program;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.event.Listener;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.network.protocol.*;
import cn.nukkit.Player;
import cn.nukkit.utils.Config;
import java.io.File;
import java.net.InetSocketAddress;
public class pluginMV extends PluginBase implements Listener{
  private Config conf;
  @Override
  public void onEnable(){
    this.getLogger().info("§eLoading...");
    this.getServer().getPluginManager().registerEvents(this, this);
    this.getDataFolder().mkdir();
    File Conf = new File(this.getDataFolder()+"/config.yml");
    if(Conf == null){
      saveResource("config.yml");
    }
    Conf = new File(this.getDataFolder()+"/config.yml");
    this.conf = new Config(Conf, 2);
  }
  @EventHandler
  public void onDataPacketReceiveEvent(DataPacketReceiveEvent event){
    Player player = event.getPlayer();
    switch(true){
      case event.getPacket() instanceof LoginPacket: {
        this.loginServ(player, event.getPacket());
        break;
      }
    }
  }
  private void setCansel(DataPacketReceiveEvent event){
    try{
      event.setCancelled(true);
    }catch(Exception err){
      
    }
  }
  private void loginServ(Player player, LoginPacket packet){
    String xuid = "";
    String username = player.getName();
    String uuid = player.getUniqueId().toString();
    InetSocketAddress mcIP = new InetSocketAddress(this.conf.getString("ip"), this.conf.getInt("port"));
  }
}
