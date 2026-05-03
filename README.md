# TankBriga 🚀

TankBriga é um jogo de artilharia multiplayer para Android inspirado no clássico Gunbound. Desenvolvido com foco em performance e precisão física, o jogo utiliza uma engine determinística distribuída e áudio processual para oferecer uma experiência leve e imersiva.

## ✨ Destaques

- **Engine Física Determinística:** Sincronização perfeita entre dispositivos baseada em sementes de mapa.
- **Terreno Destrutível:** Crateras reais e permanentes que afetam a movimentação e estratégia.
- **Bônus de Ângulo Alto:** Tiros acima de 70° concedem +50% de dano extra.
- **Áudio & Haptics Processuais:** Feedback sonoro e tátil gerado em tempo real via código (zero assets externos).
- **Performance:** Rodando a 60 FPS estáveis mesmo em dispositivos modestos.
- **Modo Solo:** IA avançada com diferentes níveis de habilidade (Snipers, Bombers, Amadores).

## 🎮 Controles

- **MIRA:** Deslize no slider vertical para ajustar o ângulo.
- **AJUSTE FINO:** Use os botões `[-]` e `[+]` para precisão grau a grau (segure para repetição automática).
- **FOGO:** Segure o botão para carregar a força e solte para disparar.
- **CÂMERA:** Alterne entre os modos `FOCUS`, `GENERAL` e `FREE` (com suporte a zoom).

## 📸 Screenshots

*Adicione suas fotos do jogo nesta sessão para mostrar o visual de espaço e as batalhas!*

<img width="1204" height="540" alt="WhatsApp Image 2026-05-02 at 22 40 13" src="https://github.com/user-attachments/assets/0641b74c-0bc0-4aa2-a5f6-62e12478ac21" />
<img width="1204" height="452" alt="WhatsApp Image 2026-05-02 at 22 40 42" src="https://github.com/user-attachments/assets/c8f96a03-0eaa-467c-81f1-b560aa73bf08" />


---

## 🛠️ Desenvolvimento

O projeto é dividido em dois módulos principais:
- `:engine`: Lógica pura de física, RNG determinístico e gerenciamento de estado.
- `:app`: Implementação Android, renderização via SurfaceView e sistema sensorial.

### Requisitos
- Android 6.0+ (API 23)
- Java 17 / Kotlin 1.9

---
Desenvolvido por **Walbarellos** com assistência de engenharia de IA.
