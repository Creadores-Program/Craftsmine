# CraftsMine (TEP)
Bem-vindo a este projeto que fez o impossível 😉! Imagine um servidor compatível com Minecraft Pocket ou como alguns chamam, Craftsman com Minecraft Bedrock!

Muitos me disseram que era impossível, mas você me conhece, então aceitei o desafio e aqui estou!

**ESTE É O PRIMEIRO SOFTWARE QUE PERMITE QUE JOGADORES DE MINECRAFT POCKET (0.15.x) ENTREM EM SERVIDORES MINECRAFT BEDROCK (1.7.0)!!**

## Requisitos:
- Java 8 ou superior instalado.
- Maven instalado.
- Conexão à Internet.
- A porta que você usará para abrir o servidor deve estar aberta.

## O que é isso??...
Isto é um **SOFTWARE**. Não é um **plugin** para nenhum software de servidor Minecraft.

## Como Conseguir?
Basta entrar no nosso [Discord](https://discord.com/invite/mrmHcwxXff) e obtê-lo a um preço **acessível** e **negociável**! Não espere mais!

## Como Funciona?
Este proxy traduz os datapacks do Minecraft Pocket para Minecraft Bedrock e vice-versa, algo assim:

MCPE -> CraftsMine Proxy Tradutor -> Servidor Minecraft Bedrock.

Servidor Minecraft Bedrock -> CraftsMine Proxy Tradutor -> MCPE

**Lembre-se de que este software envia o jogador do Minecraft Pocket como Minecraft Bedrock 1.7!**

## O que Funciona?
Aqui está uma lista de coisas que funcionam neste **Software**!

**----------------------------------**

✔️ **Funciona Bem**

❕ **Funciona com Bugs**

❌ **Não Funciona**

**----------------------------------**

  - ✔️ Sons
  - ✔️ Login de Datapackets do Servidor (Handshake)
  - ✔️ Skin
  - ✔️ Chat
  - ✔️ Comandos
  - ✔️ Spawn do Jogador
  - ✔️ Tempo
  - ✔️ Clima
  - ✔️ Mudança de modo de jogo (Sobrevivência, Criativo, Aventura e Espectador)
  - ❕ Efeito de Posição
  - ❕ Animações
  - ❕ Partículas
  - ❕ Lista de Jogadores
  - ❕ Interação do Jogador
  - ❕ Vida do Jogador
  - ❕ Fome do Jogador
  - ❕ Respawn
  - ❕ Multi Mundo
  - ❌ Autenticação Xbox
  - ❌ Geração de Terreno
  - ❌ Movimento
  - ❌ Entidades (incluindo jogadores)
  - ❌ Inventário
  - ❌ Níveis
  - ❌ UI/Formulários

  E mais...

## Versões Testadas do MCPE:

 - MCPE 0.15.10
 - MCPE 0.15.9

## Como Instalar:
Basta colocar o arquivo jar como inicializador do programa, por exemplo, em um painel Pterodactyl, basta colocá-lo no diretório principal e renomeá-lo para **server.jar** ou qualquer que seja o nome do arquivo jar que **inicia o servidor**! Lembre-se de que o programa está escrito em **Java**!

Para permitir que jogadores do Minecraft Pocket se juntem aos jogadores do Minecraft Bedrock 1.21 ou a versão atual, você pode usar [Nukkit PetteriM1 Edition](https://github.com/PetteriM1/NukkitPetteriM1Edition/)

## Configuração:
A configuração está no diretório principal do servidor, é o arquivo config.yml (lembre-se de ligar e desligar o servidor para gerar a configuração!)

#### bindAddress
Este é o IP onde o servidor CraftsMine abre (na maioria das vezes não é necessário modificar).

#### port
Esta é a porta onde o servidor CraftsMine abre.

#### motd
Esta é a mensagem do dia que o servidor CraftsMine mostrará.

#### submotd
Esta é a sub-mensagem do dia que aparece abaixo do motd quando o CraftsMine está em LAN.

#### maxplayers
Este é o número máximo de jogadores que o servidor CraftsMine aceita.

#### shutdownMessage
Esta é a mensagem que mostra quando o proxy é desligado.

#### bedrockAddress
Este é o IP do servidor de destino Minecraft Bedrock.

#### bedrockPort
Esta é a porta do servidor de destino Minecraft Bedrock.

#### debug
Se ativado, envia mensagens de debug para o console.

#### gcCollectionDiconnectPlayer
Se ativado, libera memória toda vez que um jogador se desconecta do servidor.

## Apoiar o Projeto

Se você quiser apoiar o projeto, pode fazer uma doação neste [link](https://creadoresgames.blogspot.com/p/donaciones.html) via transferência bancária ou PayPal!
Muito obrigado ☺️

## Créditos:

  - [Barrel CREA Edition](https://github.com/Trollhunters501/Barrel-CREA-Edition/)
  - [MBNukkit](https://github.com/Trollhunters501/MBNukkit/)
  - [DragonProxy](https://github.com/robske110/DragonProxy/)
  - [Protocol Cloudburstmc](https://github.com/CloudburstMC/Protocol/)
