# PROTOCOL

Android tool for Subaru SSM2 diagnostics and bench ECU/TCM firmware work.

> ## ⚠ Project status: UNFINISHED and EXPERIMENTAL
> PROTOCOL is a work in progress reverse engineering and diagnostics project. It is **not** a
> finished or validated product. It is published as open source **reference material**, to be
> studied, adapted, and built upon under the terms of the GPLv3, not as a turnkey tool.

## What it does

- Live SSM2 parameter logging from the ECM and TCM over K-line and CAN, across multiple adapters
  (Tactrix OpenPort, OBDLink and STN, and a plain FT232 KKL cable).
- Diagnostic trouble code read and clear.
- Bench SH7058 firmware **read and write** over ISO-TP and UDS.

## Bring your own payload

PROTOCOL ships the **transport mechanism only**. Flash kernels and ECU definitions are **not**
bundled in the app. They are user supplied external files loaded at runtime. This repository and
the built APK contain no kernel or ROM binaries.

## ⚠ Safety

PROTOCOL talks directly to vehicle control modules. Reading data is safe. Several other operations
are not:

- **Writing or flashing firmware is irreversible** and can permanently damage (brick) a module,
  affect drivability, and impact emissions compliance. Intended for bench use on a spare module
  only, with a full known good backup ROM saved and the means to reflash it if a write fails.
- **Clearing diagnostic trouble codes** clears stored fault history and can reset learned
  adaptations.
- **Manual commands** from the Developer page put raw bytes on the bus and can also damage a module
  or vehicle.

Because the software is unfinished, no operation is fully guarded. You are expected to learn the
effects of each operation yourself before using it. You, and any tuner you choose to involve, are
solely responsible for having the knowledge and judgment to use software of this kind safely.

**Use entirely at your own risk.** To the maximum extent permitted by law, the authors and
contributors accept no liability for any damage, loss, or legal consequence arising from use of this
software. No warranty, express or implied, is provided.

## License

PROTOCOL is free software licensed under the **GNU General Public License, version 3 (GPLv3)**. See
the [`LICENSE`](LICENSE) file. Bundled third party components are used under the Apache License 2.0.
