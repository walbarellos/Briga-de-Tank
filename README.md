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

<!-- 
Exemplo de como adicionar:
![Gameplay 1](link_da_imagem.png)
-->

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
