# TankBriga - Master TODO & Improvements

## Gameplay Mechanics
- [ ] **High-Angle Bonus:** Implement damage multiplier for shots above 70° (+50% dmg).
- [ ] **Low-Angle / Direct:** Calibrate low angles (0-30°) for "Short Shot" tactical play.
- [ ] **Wind Dynamics:** Ensure wind affecting high-arch shots more than low ones.
- [ ] **Physics Parity:** Verify ghost line vs real shot for 100% accuracy.

## Controls & UI
- [x] **Turn Timer:** Display prominent timer in place of compass.
- [ ] **Precision Aiming:** Add [-] and [+] buttons under the angle slider for 1° increments.
- [ ] **Threatening Angle HUD:** Display angle number at top with "menacing" aesthetic effects (glow, bold styling).
- [x] **Camera Modes:** Restore FREE, FOCUS, and GENERAL modes.
- [x] **Pinch-to-Zoom:** Enable zooming in FREE camera mode.
- [ ] **Shot Selector:** Visual button to switch between Bullet, Bomb, Ricochet, etc.

## Graphics & Aesthetics
- [x] **Background:** Deep space gradient with starfield.
- [x] **Projectile Glow:** Add glow and dense trails to bullets.
- [x] **Terrain:** Dual-layer (Grass/Soil) with partial redraw optimization.
- [ ] **Tank Variety:** Assign unique colors and slight size variations to each bot/player.
- [ ] **Impact VFX:** Larger, more dramatic explosion animations based on damage.

## Shot Types & Effects
- [ ] **Ricochet:** Bounces off terrain, explodes on 2nd hit or tank.
- [ ] **Cluster:** Splits into 3 small projectiles at the apex.
- [ ] **Vertical:** Gains speed as it falls, pierces deep into terrain.
- [ ] **Healing:** Rare shot that repairs terrain or restores small HP.

## Performance
- [x] **Object Pooling:** Use VectorPool for simulation.
- [x] **Partial Terrain Redraw:** Optimize crater rendering.
- [x] **Particle Optimization:** Use circular buffer for VFX.
