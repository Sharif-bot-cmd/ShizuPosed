# Legal Notice

**Last updated: September 21, 2026**

This document explains what ShizuPosed is, what it isn't, and where
the line between the two sits. It's written in plain language because
legal notices that nobody reads protect nobody. Read it once. If
anything in it doesn't match your situation, don't install the
software.

This is not legal advice. It's the honest position of the project's
maintainers about what we've built and how we expect it to be used.
If you need legal advice about your specific circumstances, consult
a lawyer in your jurisdiction.

---

## 1. What ShizuPosed is

ShizuPosed is a **free, open-source, non-root hook framework** for
Android. It runs Xposed-API modules in apps that are launched
through it. It does this using `app_process`, a platform binary that
exists on every Android device, invoked as the shell user (UID 2000)
via Shizuku.

Every capability ShizuPosed uses is a documented Android feature:

- **ADB** — Google's official developer debugging interface.
- **Shizuku** — a well-known Android application that brokers
  privileged shell operations to unprivileged apps.
- **The shell UID (2000)** — a real Android UID with a defined
  permission set. Not root.
- **`app_process`** — the platform binary zygote itself uses to
  start a Java runtime.

ShizuPosed does **not** use exploits. It does not:
- Escalate privilege beyond what the user has already granted.
- Bypass SELinux.
- Attack the kernel.
- Spoof cryptographic attestation.
- Steal credentials.

The source code is available on GitHub under the Apache License 2.0.
Anyone can read it, audit it, fork it, and modify it.

---

## 2. Why this software exists

The short version: because a lot of people own devices they don't
fully control, and they want to change that.

For most of Android's history, modifying how an app behaves on your
own device required either root (which voids warranty, trips
attestation, and breaks many apps) or repackaging the app (which
breaks its signature and its updates). Neither option is viable for a
large population of users — people on locked bootloaders, people
whose banking or work apps refuse to run on rooted devices, people
who simply don't want to deal with the complexity.

ShizuPosed is a way to modify the behavior of apps you choose to run,
on a device you own, without any of those costs. It reaches a subset
of what a root-based framework like LSPosed can do. In exchange, it
works on stock Android with no system modifications.

The intent is:

- **Personal customization** of apps you use.
- **Research** into how Android apps behave under modification.
- **Accessibility** for users who need behavior changes to use apps
  that don't offer them natively.
- **Preservation** of the Xposed module ecosystem, which has been
  shrinking as root becomes less viable on modern devices.

If your use case doesn't fall into one of those categories, this
software is probably not what you want. That's fine. Plenty of tools
exist for plenty of purposes.

---

## 3. What this software isn't

ShizuPosed is not a way to bypass security. It is not a tool for
accessing content or features you don't have a right to access. It
is not a way to defraud services, break license agreements, or
circumvent anti-cheat systems.

It can technically be used for those things, in the same way that a
web browser can be used to visit sites it shouldn't. The fact that a
tool can be misused doesn't make the tool illegal or unethical. But
it does mean that **the responsibility for how you use ShizuPosed is
entirely yours.**

The maintainers of ShizuPosed do not endorse, support, or condone:

- Bypassing paid features or subscriptions.
- Circumventing anti-cheat, anti-piracy, or DRM systems.
- Accessing services in violation of their terms.
- Modifying apps you don't have the right to modify.
- Attacking, defrauding, or harming other people through the use of
  this framework.

If you use ShizuPosed for any of those things, you're on your own.

---

## 4. Legality — the honest version

**No single answer applies to every user, in every country, in every
context.**

Android modding sits in a legally grey area in most jurisdictions.
The activity itself — modifying how software behaves on your own
hardware — is generally legal for personal use. What varies is:

**Device warranty.** Modifying software on your device, even without
root, may void the manufacturer's warranty. Check the warranty terms
for your specific device. In some jurisdictions (parts of the
European Union, for example), warranty law is more protective of the
consumer than the manufacturer's own terms, but that's a matter for
your jurisdiction's consumer-protection law, not something this
project can address.

**Terms of service of target apps.** Many apps — banking apps, games,
subscription services, work apps — have terms that prohibit running
them in modified environments, using modification tools, or
otherwise altering their behavior. Using ShizuPosed on those apps is
a violation of those terms, and violating terms can have
consequences (account suspension, feature lockout, etc.). We don't
enforce those terms, and we don't encourage violating them. If you
use ShizuPosed on an app with anti-modification terms, you accept
whatever consequences follow.

**Anti-circumvention law.** In some jurisdictions (the United States
under the DMCA, the European Union under the Information Society
Directive, and others), circumventing technological protection
measures is illegal, with limited exceptions. Modifying an app's
runtime behavior to bypass a lock, a licensing check, or a DRM
mechanism may fall under these laws. Personal-use exemptions exist
in some jurisdictions, but they vary widely. If you're using
ShizuPosed for anything that could be characterized as
circumventing a protection measure, consult a lawyer.

**Export control.** Software that modifies device behavior can fall
under export control regimes in some countries. ShizuPosed is
published under Apache 2.0 with no use restrictions, but users in
sanctioned jurisdictions should assume the usual export rules apply.

**Local obscurity.** Some jurisdictions have laws that predate
Android modding and haven't been updated to address it. Nobody knows
exactly what applies until it's tested in court. We can't predict
that. We can only tell you what we know about the general landscape.

**The blunt summary:**

- Modifying your own device, for your own use, is legal in most
  places. That's the position ShizuPosed is designed around.
- Violating an app's terms of service is a contract matter, not a
  criminal one, in most jurisdictions — but the consequences are
  real and can include losing access to the service.
- Circumventing a technological protection measure may be illegal
  under anti-circumvention law, with exceptions that vary.
- Nobody can promise you that any specific use of ShizuPosed is
  legal in your jurisdiction. If you need certainty, consult a
  lawyer.

---

## 5. No warranty, no liability

ShizuPosed is provided under the Apache License 2.0. That license
contains a warranty disclaimer and a limitation of liability. The
plain-language version:

**The software is provided "as is."** We make no promises about it
working, working correctly, working on your device, or doing anything
useful. We make no promises about it being free of bugs. We make no
promises about it not causing problems.

**We are not liable for anything that happens as a result of using
it.** Not to your device. Not to your apps. Not to your data. Not
to your accounts. Not to your warranty. Not to your relationships,
your employment, your legal standing, or anything else.

If any of that is unacceptable to you, don't install the software.

---

## 6. What we can and can't control

**What we control:**

- The source code. It's on GitHub. Anyone can read it, audit it, and
  fork it.
- The releases we publish. Each release is built from a specific
  commit, and that commit is public.
- The documentation in this repository. It describes what the
  software does and what its limits are.

**What we don't control:**

- **Third-party forks.** Anyone can fork ShizuPosed and distribute
  a modified version. Those forks are not our responsibility. If a
  fork does something you don't like, don't use it. If a fork is
  malicious, report it to whoever distributes it — not to us.
- **Third-party modules.** ShizuPosed runs modules written by other
  people. We don't audit them, endorse them, or guarantee anything
  about them. Installing a module is installing code you haven't
  seen, written by someone you don't know, into an app you care
  about. That's the risk you're accepting when you install it.
- **What users do with the software.** See the previous section.
- **The apps users modify.** Apps have their own terms, their own
  detection systems, their own consequences. We don't intervene in
  any of that.
- **Shizuku.** ShizuPosed depends on Shizuku. Shizuku is a separate
  project with its own maintainers, its own license, and its own
  direction. What Shizuku does or doesn't do isn't our concern, and
  what happens to your Shizuku setup isn't our responsibility.

---

## 7. On the topic of detection and hiding

ShizuPosed ships a built-in module called XStealth. XStealth hides
ShizuPosed's presence from detection checks in target apps. This is
described in the README, and it's a feature users may enable or
disable at will.

We want to be clear about what XStealth is and isn't.

**XStealth is a privacy feature.** Many apps check for signs of
modification and refuse to run if they find any. The modification
being checked for may be legitimate — a user customizing an app on
their own device — but the app's detection can't distinguish that
from hostile modification, so it flags both. XStealth lets a user
keep using an app they have every right to use, without the app
refusing to run for reasons unrelated to the user's intent.

**XStealth is not a security bypass.** It doesn't defeat hardware
attestation. It doesn't hide root from a genuinely rooted device. It
doesn't make a compromised device look secure. It hides ShizuPosed's
own footprint, and nothing else.

**The line we draw.** Using XStealth to keep an app running that
would otherwise refuse due to false-positive detection is a
reasonable use of the feature. Using XStealth to defraud a service,
bypass a security check that exists for a legitimate reason, or
access something you don't have a right to access, is not. We don't
try to enforce that line. We just state it, and we expect users to
respect it.

---

## 8. On the topic of the Xposed module ecosystem

ShizuPosed implements the `de.robv.android.xposed.*` API. That API
was created by rovo89 for the original Xposed Framework, and has been
maintained by the LSPosed project and its forks since. We're
implementing a public, documented API so that modules written for it
keep working on a non-root device.

We're grateful to:

- **rovo89**, for the original Xposed Framework and its API.
- **The LSPosed team**, for carrying the API forward after Xposed
  stopped being maintained, and for the design of the module status
  provider contract that ShizuPosed's shim is compatible with.
- **canyie**, for Pine, the primary ART hooking engine.
- **Rikka**, for Shizuku, without which none of this would be
  possible.
- **thedjchi**, whose Shizuku fork is the recommended privileged
  bridge for ShizuPosed.
- **Every module author** who has kept the Xposed-API module
  ecosystem alive through years of platform changes.

Nothing about ShizuPosed replaces those projects. It's an alternative
for a specific population of users who can't use the root-based
frameworks those projects target. It's a complement, not a
competitor.

---

## 9. Reporting concerns

If you believe ShizuPosed is being used in a way that violates the
law, the responsible thing is to contact the relevant authorities or
the affected service — not the maintainers of this project. We don't
have the ability to control how anyone uses the source code, and we
don't have access to information about any specific user's usage.

If you believe the source code itself contains something that
violates a specific law in your jurisdiction, open an issue on
GitHub with the specific concern. We'll evaluate it. We can't promise
to agree with every characterization of the code, but we'll take
specific legal concerns seriously.

If you are a rights holder and believe ShizuPosed infringes your
rights — for example, by implementing an API you claim to own, or by
enabling the modification of an app you own — contact us with the
specifics. We're willing to discuss what the appropriate response is.
We are not willing to accept blanket assertions that modifying
software you own on hardware you own is inherently wrongful.

---

## 10. The spirit of the thing

ShizuPosed exists because we believe users should be able to modify
the software they run on the devices they own, in ways that don't
harm anyone else.

That belief has limits. It doesn't extend to defrauding services,
harming other users, or bypassing protections that exist to protect
something other than a manufacturer's business model.

Within those limits, we think the ability to modify your own device
is worth defending. It's the same principle that lets you change the
oil in your own car, jailbreak an iPhone you own, install Linux on a
laptop you bought, and root your own Android phone. It's the same
principle behind the right-to-repair movement, the free software
movement, and every other effort to keep users in control of their
own hardware.

We built ShizuPosed because that principle matters. We hope you use
it in that spirit.

---

## 11. Contact

For questions about this notice, open an issue on the GitHub
repository:

https://github.com/Sharif-bot-cmd/ShizuPosed

For security concerns, use the same channel and mark the issue
appropriately. We respond to specific, actionable reports. We don't
respond to generic "this could be misused" concerns, because any
software powerful enough to be useful can be misused.

---

*ShizuPosed is licensed under the Apache License, Version 2.0. See
the LICENSE file for the full text.*