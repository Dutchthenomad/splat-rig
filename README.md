# SplatRig

Phone capture APK + private PC trainer for 3D Gaussian Splats.
Sized for an **RV / camper walkaround** on an **RTX 3080 16 GB**.

Repo: https://github.com/Dutchthenomad/splat-rig

The phone does **not** train the splat. The 3080 does.

## Camper capture protocol

Exterior = one session. Interior = a second session.

1. Park in open shade or overcast light.
2. Slow loop at chest height, 1 to 1.5 m from the walls.
3. Second loop covering roofline and awning.
4. Extra frames: wheels, hitch, rear, slide-out corners.
5. Target **120-220 sharp frames**.
6. No whip-pans. Do not rely on shots through tinted glass.

PC resizes the long edge to **1600 px** so 16 GB VRAM is enough.

## Get the APK

After the Android sources are on `main`, GitHub Actions job `android-apk` uploads `app-debug.apk`.

1. Actions tab → latest green run → Artifacts → `app-debug`
2. Sideload on the phone (allow unknown sources)
3. Grant camera, start a session, walk the loops, export zip

## PC worker

WSL2 Ubuntu or native Linux + CUDA is the reliable path on a 3080 Windows box.

```bash
cd worker
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
# sudo apt install colmap
# plus Brush from GitHub releases, or: pip install gsplat torch torchvision --index-url https://download.pytorch.org/whl/cu124
python serve.py --host 0.0.0.0 --port 8080
```

Offline:

```bash
python pipeline.py session.zip --out ./runs/camper
```

Open the `.ply` in SuperSplat: https://playcanvas.com/supersplat/editor
