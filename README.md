# Virt Manager for Android

A native Android port of [virt-manager](https://virt-manager.org/) with a modern
Material 3 / Jetpack Compose interface. Manage KVM/QEMU (and other libvirt) hosts
from your phone or tablet: start and stop guests, watch live performance graphs,
create VMs, take snapshots, and open the graphical (VNC) or serial console.

<p>
  <img src="docs/screenshots/01-connections.png" width="200" alt="Connections">
  <img src="docs/screenshots/03-machines.png" width="200" alt="Virtual machines">
  <img src="docs/screenshots/07-vm-overview.png" width="200" alt="VM overview">
  <img src="docs/screenshots/03-machines-dark.png" width="200" alt="Dark theme">
</p>
<p>
  <img src="docs/screenshots/04-host-overview.png" width="200" alt="Host overview">
  <img src="docs/screenshots/08-vm-hardware.png" width="200" alt="Hardware">
  <img src="docs/screenshots/09-new-vm-media.png" width="200" alt="New VM wizard">
  <img src="docs/screenshots/06-storage.png" width="200" alt="Storage">
</p>
<p>
  <img src="docs/screenshots/14-hw-overview.png" width="200" alt="Hardware: Overview page">
  <img src="docs/screenshots/15-add-hardware.png" width="200" alt="Add Hardware">
  <img src="docs/screenshots/13-delete-vm.png" width="200" alt="Delete VM">
</p>

> The screenshots are recorded by `AppScreenshotTest`, which drives the real app over real SSH
> against libvirt's built-in test driver.

## How it works

virt-manager talks to remote hosts with `qemu+ssh://` URIs, i.e. it tunnels libvirt over SSH.
This port does the same thing without shipping libvirt for Android: the app opens an SSH
session to the host and drives the host's own `virsh`, parsing its output (and domain XML)
into typed models. Consoles reuse the same SSH session:

| Feature | Mechanism |
| --- | --- |
| VM list, live CPU / memory / disk / network graphs | `virsh domstats --raw` polled every 2 s, batched in one SSH round trip |
| Lifecycle (start, shut down, reboot, pause/resume, save, reset, force off) | `virsh start/shutdown/...` |
| Details, hardware, XML | `virsh dumpxml` parsed with a DOM parser |
| Edit vCPUs / memory, CD-ROM insert/eject, rename, autostart, delete | `virsh setvcpus/setmem/change-media/domrename/autostart/undefine` |
| virt-manager's details pages (Overview, OS information, CPUs, Memory, Boot options, every device), Add Hardware, Remove, per-device and full XML editing | `virsh dumpxml --inactive --security-info` → edit → `virsh define`; options filtered by `virsh domcapabilities` |
| Snapshots | `virsh snapshot-*` |
| New VM wizard | `virsh vol-create-as` + generated domain XML via `virsh define /dev/stdin` |
| Networks and storage pools/volumes | `virsh net-*`, `pool-*`, `vol-*` |
| Graphical console | Built-in RFB/VNC client through an SSH local port forward (TCP or UNIX socket displays, VNC password auth) |
| Serial console | `virsh console` in an SSH PTY |

Everything runs with `LC_ALL=C` so parsing never depends on the host locale, and every
argument is shell-quoted.

### Features

- **Connections** – multiple hosts, `qemu:///system`, `qemu:///session`, LXC, Xen or any
  custom libvirt URI; password or SSH key auth (paste, import, or generate an Ed25519 key in
  the app and copy the public key); optional `sudo -n`; SSH compression; auto-connect.
- **Jump hosts** – like OpenSSH `ProxyJump`: a connection can go through another saved host
  (which can itself use one). The second SSH session runs inside a `direct-tcpip` channel of
  the first, so the jump host needs `AllowTcpForwarding` but neither libvirt nor netcat, and no
  local port is opened for it. Every hop has its own login, password prompt, host key and
  keepalives; errors name the hop. Hosts without libvirt can be saved as "SSH only".
- **Security** – trust-on-first-use host key verification with fingerprint confirmation and a
  loud warning if a key changes; stored passwords/keys are encrypted with an AES-GCM key held
  in the Android Keystore; backups of app data are disabled.
- **Machines** – search, filter by state, per-VM CPU and memory sparklines, one-tap primary
  action plus a full action menu; confirmations for destructive actions; VMs whose
  customize-before-install setup isn't finished are marked "Setup not finished".
- **VM details** – Status (CPU, memory, disk I/O and network tiles with history), Hardware,
  Snapshots, Settings (autostart, rename, XML editor, delete).
- **Delete** – like virt-manager: each of the VM's disk images has its own checkbox; images
  another VM also uses start unchecked and say which VM; install media are never offered.
  The VM is forced off and undefined with its managed save, snapshot metadata, NVRAM and TPM
  state, and files that libvirt can't delete (not in an active storage pool) are listed
  afterwards instead of being silently left behind.
- **Hardware** – the same list as virt-manager's details window, each entry with a Details form
  (and an XML tab for devices) plus Remove:
  - *Overview*: title, description, chipset (Q35/i440FX), firmware (BIOS/UEFI/Secure Boot)
  - *OS information* (libosinfo variant), *CPUs* (current/maximum vCPUs, host-passthrough,
    host-model, maximum or a named model, manual topology), *Memory* (current/maximum, shared
    memory), *Boot options* (autostart, boot menu, device boot order)
  - disks and CD-ROMs (source, bus, format, cache, discard, read-only, shareable, serial; live
    media change), NICs (virtual network/bridge/macvtap with mode/usermode, model, MAC, link
    state), input, graphics (VNC/SPICE, listen address/UNIX socket/none, port, password,
    OpenGL), sound, video (model, heads, 3D),
    serial/parallel/console/channels, controllers, USB redirection, RNG, watchdog, TPM,
    filesystem (virtiofs/9p), panic notifier, VSOCK, smartcard, host devices
  - **Add Hardware** with every type virt-manager offers: storage (new image or existing),
    controller, network, input, graphics, sound, serial, parallel, console, channel, USB and
    PCI host devices (every device the host has, lspci-style with class, IDs, driver and
    IOMMU group, searchable, with bridges and already-attached devices marked; devices that
    libvirt's own list leaves out, such as a GPU that stopped answering after a failed reset,
    are found in sysfs and flagged), video, watchdog, filesystem, smartcard, USB
    redirection, TPM, RNG, panic notifier, VirtIO VSOCK
  - option lists come from the host's `domcapabilities`, so only what the hypervisor supports
    is offered; SPICE-only features are only offered with a SPICE display
- **New VM wizard** – virt-manager's four steps: install media (ISOs discovered in the host's
  pools, or a path), memory and CPUs (bounded by the host), storage (qcow2 in a chosen pool),
  network (virtual network / bridge / usermode / none) plus UEFI, start-after-create and
  **Customize configuration before install** (define only, review all hardware as above, then
  **Begin installation** or **Cancel installation**; leaving asks first, and an unfinished
  setup can be resumed later from the machine list, on any device). Creation can't be
  interrupted half-way. Bridges are discovered on the host and validated before anything is created,
  inactive networks are started with the VM, a failed step rolls back the new disk, leftover
  images never block a retry, and KVM-less hosts get software-emulated guests. When QEMU
  can't open an image because its user may not enter a folder above it (e.g. an ISO in a home
  folder), the VM page says which folders and offers virt-manager's fix: search permission for
  QEMU's user via `setfacl`, then starts the VM. OS-aware
  defaults: virtio for Linux/BSD, SATA + e1000e + Hyper-V enlightenments for Windows, `virt`
  machine + UEFI on aarch64 hosts.
- **Host** – host CPU and memory graphs, committed memory, hypervisor/libvirt versions, CPU
  topology. **Networks** – start/stop, autostart, mode, bridge, DHCP range. **Storage** –
  pool usage, start/stop/refresh, browse and delete volumes.
- **Consoles** – immersive VNC viewer with pinch-zoom/pan, tap = click, long-press = right
  click, drag = drag, two-finger scroll, and a Termux-style extra-keys bar above the soft
  keyboard (ESC / - HOME ↑ END PGUP FN, TAB CTRL ALT ← ↓ → PGDN DEL; FN adds SUPER, SHIFT,
  INS and F1–F12). Modifiers apply to the next key, typed ones included, or stay on with a long
  press; arrows repeat while held; swipe up on / and - for \ and |. Ctrl+Alt+Del and
  Ctrl+Alt+F1–F8 are in the menu. Plus a serial terminal with quick keys.
- **Modern UI** – Material 3, dynamic color (Android 12+) with a matching fallback palette,
  light/dark/system theme, edge-to-edge, animated navigation.

## Host requirements

- An SSH server and `virsh` (package `libvirt-clients` / `libvirt-client`) on the host.
- The SSH user must be allowed to use the chosen URI: for `qemu:///system` that usually means
  membership in the `libvirt` group, logging in as root, or enabling **Use sudo** with
  passwordless sudo for `virsh`.
- For the graphical console the VM needs a VNC display (VMs created by the app get one
  listening on `127.0.0.1`, which is reached through the SSH tunnel). SPICE-only VMs can use
  the serial console or get a VNC display added.

## Building

Requirements: JDK 17+, Android SDK with platform 35.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug
```

`minSdk` is 26 (Android 8.0). The project uses Kotlin 2.0, Compose (BOM 2024.12), Navigation
Compose with type-safe routes, DataStore, [JSch (mwiede fork)](https://github.com/mwiede/jsch)
for SSH and Bouncy Castle for Ed25519/Curve25519 (Android ignores the multi-release JDK
classes JSch would otherwise use, so the app configures JSch's Bouncy Castle implementations).

### Tests

The unit tests cover the virsh output parsers, domain XML generation (round-tripped through
the parser), command quoting and batching, metrics math, the serial terminal model, and the
RFB client against an in-process fake VNC server.

Opt-in integration tests run against real services when these variables are set:

| Variable | Purpose |
| --- | --- |
| `VMA_IT_SSH_PORT`, `VMA_IT_SSH_KEY` (`VMA_IT_SSH_USER`, default `root`) | sshd on 127.0.0.1 with `virsh` installed. `SshIntegrationTest` uses `test:///default` to cover host-key TOFU, polling, XML, stdin, PTY and port forwarding. |
| `VMA_IT_JUMP_PORT`, `VMA_IT_NOFWD_PORT` | Two more sshds on 127.0.0.1 for the same key, the second with `AllowTcpForwarding no`. `JumpHostIntegrationTest` runs `virsh` and a console tunnel on the `VMA_IT_SSH_PORT` host through the jump host, and checks per-hop host keys and that errors (forwarding refused, unreachable target, wrong key, closed jump session) land on the right hop. |
| `VMA_IT_VNC_PORT`, `VMA_IT_VNC_PW_PORT` + `VMA_IT_VNC_PW` | QEMU VNC displays (e.g. `qemu-system-x86_64 -display none -vnc 127.0.0.1:5`). `QemuVncIntegrationTest` renders the framebuffer and checks VNC password auth through the SSH tunnel. |
| `VMA_IT_QEMU` | `qemu-system-x86_64` installed locally. `QemuKeyboardIntegrationTest` starts QEMU with key tracing and checks that typed symbols such as `!` reach the guest with Shift. |
| `VMA_IT_TESTNODE` | Path to a libvirt test-driver node XML on that host; `AppScreenshotTest` runs the app under Robolectric and records `docs/screenshots`. |
| `VMA_IT_REAL_URI` | A real libvirtd URI on that host (e.g. `qemu:///system`, with a `default` pool holding an ISO). `RealHostWizardTest` drives the New VM wizard through bridge validation, inactive networks, leftover images, a VM that fails to start, customize-before-install, the hardware pages, Add Hardware (including a PCI host device), Remove, the XML editors, leaving and resuming an unfinished setup, and Cancel installation. `RealSnapshotTest` (local `virsh`) takes, reverts and deletes internal and external snapshots, and deletes a VM with external ones. `RealDeleteTest` (local `virsh`) deletes VMs with shared disks, disks outside active pools, volume disks and a running UEFI VM with TPM. `RealLibvirtConfigTest` (local `virsh`) saves every editor field and all Add Hardware types through libvirt and boots an edited VM. They create and delete their own VMs. With `VMA_IT_HOME_USER` (a user in the libvirt group with a 0700 home folder), `RealQemuAccessTest` reproduces "Permission denied" for an ISO in that home folder and fixes it as that user. |

## Project layout

```
app/src/main/java/dev/virtmanager/android/
├── libvirt/   Models, virsh output/XML parsers, VirshClient, domain XML builder
├── ssh/       JSch session: exec, PTY, port forwarding, TOFU host keys, key generation
├── vnc/       RFB client and X11 keysyms
├── data/      Connection profiles (DataStore), Keystore secret encryption,
│              connection manager, metrics tracker
└── ui/        Compose screens: connections, host (machines/overview/networks/storage),
               VM details, new VM wizard, VNC and serial consoles, theme and components
```

## Limitations

- No SPICE client; the VNC client supports Raw and CopyRect encodings (plus DesktopSize),
  which QEMU always offers. SSH compression is enabled by default to keep this fast.
- Talks to libvirt via `virsh` rather than the libvirt RPC protocol, so the host needs
  `virsh` and SSH access (exactly what `qemu+ssh://` in virt-manager needs as well).
- Migration and device hot-plug are not implemented yet.
