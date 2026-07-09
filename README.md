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

## Notas sobre RFID UHF

O UHF (~900 MHz) é apenas *fracamente* direcional e sofre reflexão (multipath) em
paredes e prateleiras metálicas. O foco preciso vem de **reduzir a potência** de
leitura, não de "apontar" — o app faz isso no modo de busca.
