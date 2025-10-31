package org.CreadoresProgram.CraftsMine.player;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.cloudburstmc.netty.channel.raknet.RakChannelFactory;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.data.*;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import lombok.Getter;
import lombok.Setter;
import org.CreadoresProgram.CraftsMine.server.Server;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.CreadoresProgram.CraftsMine.network.translator.PacketTranslatorManager;
import org.CreadoresProgram.CraftsMine.server.Server;
import org.CreadoresProgram.CraftsMine.options.Config;
import org.CreadoresProgram.CraftsMine.network.BedrockBatchHandler;
import org.CreadoresProgram.CraftsMine.utils.Utils;
import org.CreadoresProgram.CraftsMine.network.protocol.base.MCDPacket;
import org.CreadoresProgram.CraftsMine.network.protocol.DisconnectPacket;
import org.CreadoresProgram.CraftsMine.nukkitLib.entity.data.Skin;
import org.CreadoresProgram.CraftsMine.inventory.Item;
import org.CreadoresProgram.CraftsMine.network.translator.managerScreen.ScreenManager;

public class Player extends Vector3{
  @Getter
  private String identifierCraftsMan;
  @Getter
  private BedrockClientSession bedrockClientSession;
  @Getter
  private final PacketTranslatorManager packetTranslatorManager;
  @Getter
  private Channel channel;
  @Getter
  private String craftsmanUsername;
  @Getter
  private String username;
  @Getter
  private String UUID;
  @Getter
  private ECPublicKey publicKey;
  @Getter
  private String craftsmanPublicKey;
  @Getter
  private ECPrivateKey privateKey;
  @Getter
  private long clientID;

  @Getter
  @Setter
  private Item[] hotbar;

  @Getter
  @Setter
  private Item[] inventory;
  
  @Getter
  @Setter
  private long runtimeEntityId;

  @Getter
  @Setter
  private int gamemode;

  @Getter
  private Skin skinC;

  private String skinId;

  private long nbf;

  private long exp;

  @Getter
  private final ScheduledExecutorService playerInputExecutor = Executors.newScheduledThreadPool(1);
  @Getter
  @Setter
  private boolean playerInputAuth = false;

  @Getter
  @Setter
  private long tikPia = 0;

  @Getter
  @Setter
  private boolean sneaking = false;

  @Getter
  @Setter
  private boolean sprinting = false;

  @Getter
  @Setter
  private PlayerActionType diggingStatus = PlayerActionType.STOP_BREAK;

  @Getter
  @Setter
  private Vector3i diggingPosition = Vector3i.from(0, 0, 0);

  @Getter
  @Setter
  private int diggingFace = 0;

  @Getter
  @Setter
  private Vector3f oldPosition;

  @Getter
  private Map<Long, Long> uniquesEntitysIds = new HashMap<>();

  @Getter
  private Map<Integer, String> scoreBoard = new TreeMap<>();

  @Getter
  private Map<Long, Object[]> bossBars = new HashMap<>();

  @Getter
  private ScreenManager screenManager;
  
  public Player(String indentifier){
    this.identifierCraftsMan = indentifier;
    this.packetTranslatorManager = new PacketTranslatorManager(this);
    this.screenManager = new ScreenManager(this);
  }
  private void login(org.CreadoresProgram.CraftsMine.network.protocol.LoginPacket loginpack){
    this.username = this.craftsmanUsername = loginpack.username;
    this.UUID = loginpack.clientUUID.toString();
    this.clientID = loginpack.clientId;
    this.craftsmanPublicKey = loginpack.identityPublicKey;
    Config config = Server.getInstance().getConfig();
    try{
      InetSocketAddress bedrockAddress = new InetSocketAddress(config.getBedrockAddress(), config.getBedrockPort());
      this.channel = new Bootstrap().channelFactory(RakChannelFactory.client(NioDatagramChannel.class))
        .group(new NioEventLoopGroup())
        .option(RakChannelOption.RAK_PROTOCOL_VERSION, Server.getInstance().getBedrockPacketCodec().getRaknetProtocolVersion())
        .handler(new BedrockClientInitializer(){
          @Override
          protected void initSession(BedrockClientSession session){
            bedrockClientSession = session;
            session.setCodec(Server.getInstance().getBedrockPacketCodec());
            // Wrap existing batch handler with legacy rewriter so packets from server are converted before
            // reaching the translator.
            org.cloudburstmc.protocol.bedrock.handler.BedrockPacketHandler batch = new org.CreadoresProgram.CraftsMine.network.BedrockBatchHandler(Player.this);
            session.setPacketHandler(new org.CreadoresProgram.CraftsMine.network.LegacyPacketRewriter(Player.this, batch));
            RequestNetworkSettingsPacket requestNetworkSettingsPacket = new RequestNetworkSettingsPacket();
            requestNetworkSettingsPacket.setProtocolVersion(Server.getInstance().getBedrockPacketCodec().getProtocolVersion());
            session.sendPacketImmediately(requestNetworkSettingsPacket);
          }
        })
        .connect(bedrockAddress)
        .awaitUninterruptibly().channel();
    }catch(Exception exception){
      org.CreadoresProgram.CraftsMine.network.protocol.LoginStatusPacket pk = new org.CreadoresProgram.CraftsMine.network.protocol.LoginStatusPacket();
      pk.status = org.CreadoresProgram.CraftsMine.network.protocol.LoginStatusPacket.LOGIN_SUCCESS;
      this.sendDataCraftsman(pk);
      this.disconnect("Failed to connect: "+ exception + " or Server offline");
    }
  }
  public void onLogin(org.CreadoresProgram.CraftsMine.network.protocol.LoginPacket loginpack){
    this.skinC = loginpack.skin;
    this.skinId = loginpack.skinId;
    this.nbf = loginpack.nbf;
    this.exp = loginpack.exp;
    this.login(loginpack);
  }
  public LoginPacket getLoginPacket(){
    LoginPacket loginPacket = new LoginPacket();
    KeyPair ecda384KeyPair = EncryptionUtils.createKeyPair();
    this.publicKey = (ECPublicKey) ecda384KeyPair.getPublic();
    this.privateKey = (ECPrivateKey) ecda384KeyPair.getPrivate();
    String publicKeyBase64 = Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
    JSONObject chain = new JSONObject();
    chain.put("exp", this.exp);
    chain.put("identityPublicKey", publicKeyBase64);
    chain.put("nbf", this.nbf);
    JSONObject extraData = new JSONObject();
    extraData.put("identity", this.UUID);
    extraData.put("XUID", "");
    extraData.put("displayName", this.username);
    chain.put("extraData", extraData);
    JSONObject jwtHeader = new JSONObject();
    jwtHeader.put("alg", "ES384");
    jwtHeader.put("x5u", publicKeyBase64);
    String jwt = generateJwt(jwtHeader, chain);
    JSONArray chainDataJsonArray = new JSONArray();
    chainDataJsonArray.add(jwt);
    for(Object o : chainDataJsonArray){
      loginPacket.getChain().add((String) o);
    }
    loginPacket.setExtra(this.getSkinData());
    loginPacket.setProtocolVersion(Server.getInstance().getBedrockPacketCodec().getProtocolVersion());
    return loginPacket;
  }
  private String getSkinData(){
    String publicKeyBase64 = Base64.getEncoder().encodeToString(this.publicKey.getEncoded());
    JSONObject jwtHeader = new JSONObject();
    jwtHeader.put("alg", "ES384");
    jwtHeader.put("x5u", publicKeyBase64);
    JSONObject skinData = new JSONObject();
    skinData.put("CapeData", "");
    skinData.put("CapeImageHeight", 0);
    skinData.put("CapeImageWidth", 0);
    skinData.put("ClientRandomId", this.clientID);
    skinData.put("CompatibleWithClientSideChunkGen", false);
    skinData.put("CurrentInputMode", 2);
    skinData.put("DefaultInputMode", 2);
    skinData.put("DeviceId", java.util.UUID.randomUUID().toString());
    skinData.put("DeviceModel", "CraftsMine");
    skinData.put("DeviceOS", 1);
    skinData.put("GameVersion", Server.getInstance().getBedrockPacketCodec().getMinecraftVersion());
    skinData.put("GuiScale", 0);
    skinData.put("LanguageCode", Server.getInstance().getConfig().getLangPlayer());
    skinData.put("PlatformOfflineId", "");
    skinData.put("PlatformOnlineId", "");
    skinData.put("SelfSignedId", this.UUID);
    skinData.put("ServerAddress", Server.getInstance().getConfig().getBedrockAddress() + ":" + Server.getInstance().getConfig().getBedrockPort());
    if(this.skinC.getData() != null){
      skinData.put("SkinData", Base64.getEncoder().encodeToString(this.skinC.getData()));
    }else{
      skinData.put("SkinData", Server.getInstance().getDefaultSkinData());
    }
    if(this.skinC.getModel().getBytes() != null){
    skinData.put("SkinGeometry", Base64.getEncoder().encodeToString(this.skinC.getModel().getBytes()));
    }else{
      skinData.put("SkinGeometry", Base64.getEncoder().encodeToString(Server.getInstance().getDefaultSkinGeomety().getBytes()));
    }
    skinData.put("SkinGeometryName", "geometry.humanoid.custom");
    if(this.skinId == null){
    skinData.put("SkinId", "Standard_Custom");
    }else{
      skinData.put("SkinId", this.skinId);
    }
    skinData.put("SkinImageHeight", 64);
    skinData.put("SkinImageWidth", 64);
    skinData.put("ThirdPartyName", this.username);
    skinData.put("ThirdPartyNameOnly", false);
    skinData.put("UIProfile", 0);
    skinData.put("IsEditorMode", 0);
    skinData.put("TrustedSkin", 1);
    skinData.put("SkinGeometryDataEngineVersion", Base64.getEncoder().encodeToString(Server.getInstance().getBedrockPacketCodec().getMinecraftVersion().getBytes()));
    skinData.put("OverrideSkin", false);
    return generateJwt(jwtHeader, skinData);
  }
  private String generateJwt(JSONObject jwtHeader, JSONObject chain) {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(jwtHeader.toJSONString().getBytes());
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(chain.toJSONString().getBytes());

        byte[] dataToSign = (header + "." + payload).getBytes();
        byte[] signatureBytes = null;
        try {
            Signature signature = Signature.getInstance("SHA384withECDSA");
            signature.initSign(this.privateKey);
            signature.update(dataToSign);
            signatureBytes = Utils.DERToJOSE(signature.sign(), Utils.AlgorithmType.ECDSA384);
        } catch (NoSuchAlgorithmException | InvalidKeyException | SignatureException ignored) {
        }
        String signatureString = Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);

        return header + "." + payload + "." + signatureString;
  }
  public void sendMessage(String message) {
    org.CreadoresProgram.CraftsMine.network.protocol.TextPacket pk = new org.CreadoresProgram.CraftsMine.network.protocol.TextPacket();
    pk.type = org.CreadoresProgram.CraftsMine.network.protocol.TextPacket.TYPE_RAW;
    pk.message = message;
    this.sendDataCraftsman((MCDPacket) pk);
  }
  public void sendTip(String message) {
    this.screenManager.sendTip(message);
  }
  public void sendDataCraftsman(MCDPacket pk){
    Server.getInstance().getRakNetServ().sendPacket(this.identifierCraftsMan, pk, false);
  }
  public void disconnect(String reason) {
    playerInputExecutor.shutdown();
    try {
            this.bedrockClientSession.disconnect();
    } catch (Throwable ignored) {
    }
    DisconnectPacket dicpack = new DisconnectPacket();
      dicpack.message = reason;
      this.sendDataCraftsman((MCDPacket) dicpack);
    if (this.channel != null && this.channel.isOpen()) {
            this.channel.disconnect();
            this.channel.parent().disconnect();
    }
    if(Server.getInstance().getConfig().isGcCollectionDiconnectPlayer()){
      System.gc();
    }
    Server.getInstance().getLogger().info(craftsmanUsername + " disconnected: " + reason);
  }
}
