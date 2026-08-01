# PROTOCOL Android Subaru SSM2 Datalogger and ECU Flashing

PROTOCOL is a Subaru tuning and diagnostics app that runs on Android. It reads live engine and
transmission data over SSM2, reads and clears diagnostic trouble codes, and reads and writes ECU
firmware, directly from a phone with no laptop and no dedicated handheld.

Built by Cross Starling. Free software under the GPLv3.

> ## ⚠ Project status: UNFINISHED and EXPERIMENTAL
> PROTOCOL is a work in progress reverse engineering and diagnostics project. It is **not** a
> finished or validated product. It is published as open source **reference material**, to be
> studied, adapted, and built upon under the terms of the GPLv3, not as a turnkey tool.

## What it does

- Live SSM2 datalogging from the engine control module and the transmission control module, over
  both K line and CAN, with configurable gauges, min and max tracking, and CSV export.
- Displays data in multiple formats, including raw hex for decoding.
- Diagnostic trouble codes, read and clear, with a decoded fault catalogue.
- ECU firmware read and write on Renesas SH7058 modules over ISO TP (ISO 15765-2) and UDS.
- Wear OS remote, so logging can be started, stopped, and switched between parameter presets from a
  watch while the phone stays mounted.
- Raw command console for manual bus work, sequence building, and adapter bring up.

## Adapters

- Tactrix OpenPort 2.0 and clones, over USB OTG, K line and CAN.
- OBDLink MX+, over Bluetooth, K line and CAN.
- OBDLink EX, over USB, K line and CAN.
- Generic FT232RL VAG KKL cable, over USB, K line only. There is no adapter firmware in the loop
  with this cable, so the phone itself is the SSM2 protocol master.

## Bring your own payload

PROTOCOL ships the **transport mechanism only**. Flash kernels and ECU definitions are **not**
bundled in the app. They are user supplied external files loaded at runtime. This repository and
the built APK contain no kernel or ROM binaries.

Parameter definitions are ingested from standard logger definition XML, so the parameter set is
data rather than something hard coded into the app.

## Goals

- Calibration map editing on the device, so a ROM image can be opened against its definition and
  its tables edited on the phone. Desktop software does this. Nothing on a phone does it yet.
- Definition driven vehicle support, so adding a car becomes a definition file rather than a code
  change, and coverage can grow without touching Kotlin.

## Built with

100% Kotlin and Jetpack Compose, with one third party dependency. No accounts, no telemetry, no
cloud services, and no analytics. The app talks to your car and to nothing else.

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
