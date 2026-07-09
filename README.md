# Localizador RFID — Urovo DT50P

App Android para **encontrar itens no estoque** com o coletor UHF RFID Urovo DT50P.
Cadastra-se uma lista de etiquetas (EPCs) e o app ajuda a localizá-las de duas formas.

## Modos

- **🔎 Procurar um item** — localizador "quente/frio" para uma etiqueta específica.
  A tela inteira muda de temperatura conforme o sinal (frio → morno → quente) e
  **inunda de verde ("AQUI!")** quando você mira/aproxima do alvo. Contínuo: nunca
  marca como encontrado nem para sozinho. Inclui **controle de foco** que reduz a
  potência do leitor para criar uma "bolha" curta e forçar o apontamento correto.
- **☑ Conferir lista** — checklist de vários itens: os cards ficam **verdes** à
  medida que as etiquetas são lidas, com contador e feedback sonoro/tátil.
- **⚙ Gerenciar itens** — cadastro dos EPCs (digitar/colar `EPC` ou `EPC,Nome`),
  ou capturar o alvo **escaneando a etiqueta** (sem digitar o EPC).
- **📡 Conexão** — configura e monitora o envio de leituras para um servidor
  via WebSocket. Reconecta automaticamente com backoff, enfileira offline, mostra
  status ao vivo na Home com bolinha de indicador.

## Dispositivo

Urovo **DT50P** (Android, módulo UHF RFID). Deve funcionar em outros coletores
Urovo com o mesmo SDK (DT50, RT40 etc.), possivelmente com ajuste dos keycodes do
gatilho físico.

## SDK

Usa o SDK oficial da Urovo (`com.ubx.usdk`), distribuído em
[urovosamples/RFIDSDKSample](https://github.com/urovosamples/RFIDSDKSample).
A biblioteca `app/libs/URFIDLibrary-v2.5.0718.aar` está incluída para o build.
Todo contato com o SDK é isolado em [`RfidService`](app/src/main/java/br/com/estoque/rfid/rfid/RfidService.kt).

## Build e instalação

Requer Android Studio (ou JDK do Android Studio) e um dispositivo Urovo.

```bash
# compilar
./gradlew assembleDebug

# instalar via USB (depuração USB ativa no coletor)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Calibração

Constantes ajustáveis no topo de
[`FindingActivity.kt`](app/src/main/java/br/com/estoque/rfid/ui/FindingActivity.kt):

| Constante | O que faz |
|-----------|-----------|
| `DEFAULT_FOCUS` | Potência inicial de busca (menor = alcance mais curto/preciso) |
| `LOCK_ON` / `LOCK_OFF` | Limiares de "mira travada" (verde), com histerese |
| `SMOOTHING` | Suavização do sinal (maior = mais responsivo) |
| `RSSI_MAX` | Topo da escala de RSSI para o mapa 0–100 |

O gatilho físico usa os keycodes `515`/`523` (em `RfidService.TRIGGER_KEYCODES`).

## Estrutura

```
app/src/main/java/br/com/estoque/rfid/
├─ rfid/RfidService.kt        # único ponto de contato com o SDK Urovo
├─ data/                      # StockItem + ItemRepository (SharedPreferences/JSON)
└─ ui/                        # Splash, Home, TargetSelect, Finding, Checklist, ItemList
```

## Envio de dados (WebSocket)

O app pode **enviar as leituras capturadas para um servidor** via WebSocket (usando OkHttp).

### Configuração

Tela **📡 Conexão** → Home:
- **Endereço do servidor**: `ws://192.168.0.10:8080` ou `wss://...` (TLS).
- **Nome do aparelho**: identificador (ex.: "Coletor-01").
- **Token** (opcional): enviado como `Authorization: Bearer <token>`.
- **Auto-envio**: ativar/desativar envio automático.

### Comportamento

- **Reconecta automaticamente** com backoff exponencial (1s → 30s) se cair.
- **Fila offline** (até 300 msgs): se sem conexão, guarda as leituras e envia tudo ao reconectar.
- **Persiste configuração** entre sessões.
- Reconecta ao abrir o app se você havia deixado conectado.

### Formato das mensagens

Cada leitura é um JSON:

```json
{
  "type": "tag_read",
  "epc": "E280694A0200050...",
  "rssi": 73,
  "mode": "checklist",
  "found": true,
  "device": "Coletor-01",
  "ts": 1720000000000
}
```

- **type**: `tag_read` (leitura) ou `test` (teste da conexão).
- **epc**: código da etiqueta (normalizado, uppercase).
- **rssi**: intensidade do sinal (apenas em modo finding/checklist).
- **mode**: `checklist`, `finding`, ou `capture`.
- **found**: `true` se a etiqueta é do alvo (finding) ou da lista (checklist).
- **device**: nome configurado.
- **ts**: timestamp Unix em ms.

### Implementação

[`UplinkService`](app/src/main/java/br/com/estoque/rfid/net/UplinkService.kt) é o único
ponto de contato com o servidor (padrão semelhante ao `RfidService`). Chamadas:

```kotlin
UplinkService.init(context)                                    // init (uma vez)
UplinkService.saveConfig(url, device, token, autoSend)        // salvar config
UplinkService.connect()                                         // conectar
UplinkService.sendTagEvent(epc, rssi, mode, found)           // enviar leitura
UplinkService.disconnect()                                      // desconectar
```

Observar mudanças de estado:
```kotlin
UplinkService.onStateChanged = { state, error -> /* renderizar */ }
```

### Teste local

Servidor WebSocket simples em Node.js:

```bash
npm install ws
node -e "
const { WebSocketServer } = require('ws');
new WebSocketServer({ port: 8080 }).on('connection', s =>
  s.on('message', m => console.log('recebido:', m.toString()))
);
"
```

No app: **Conexão** → `ws://192.168.X.Y:8080` (IP do PC na rede) → **Conectar** → **Enviar teste**.

## Notas sobre RFID UHF

O UHF (~900 MHz) é apenas *fracamente* direcional e sofre reflexão (multipath) em
paredes e prateleiras metálicas. O foco preciso vem de **reduzir a potência** de
leitura, não de "apontar" — o app faz isso no modo de busca.
