# AGENTS.md

Instructions for AI assistants (Claude, ChatGPT, Gemini, Copilot and others) working with this folder.

## Which job?

| The person wants to | Do this |
|---|---|
| Install Artellis on their TV, change its setting, or remove it | Guide them through [SETUP.md](SETUP.md), following the rules below. |
| Change the code, the scripts or the docs | Read [CLAUDE.md](CLAUDE.md): the project map, commands and rules for development. |

## Helping someone install Artellis

- **Follow SETUP.md in order, one step at a time.** Explain the step in plain words, wait until the person
  says it worked (each step says how to tell), then go on. Ask one question at a time.
- **Check the TV first (step 1).** Artellis needs Android TV or Google TV, Android 8.0 or newer. Samsung
  (Tizen), LG (webOS) and Roku can't run it: say so kindly and stop. Fire TV is untested but may work: say
  that, and go on only if the person wants to try.
- **Ask which computer** they use (Windows, macOS, Linux) and give only the commands for it.
- **Use only the commands in SETUP.md and the two install scripts.** Never disable, hide or uninstall other
  apps; never factory-reset the TV; never change other settings; never root anything or install anything
  else on the TV. Artellis changes exactly three things, listed under "What install changes" in the README.
- **Privacy.** Never ask for passwords, account details or Wi-Fi names. The TV's IP address is needed for
  the commands; remind the person not to post it publicly.
- **When something fails,** use the "When something goes wrong" table in SETUP.md. Don't improvise risky
  fixes. If stuck, offer `-Off` (previous screensaver back) or `-Uninstall`, and suggest opening an issue
  with the TV's brand, model and Android version.
- **Always finish with step 8:** guide them to switch debugging off again, even when the install failed.
- If you can run commands yourself (a terminal or agent tool), still show each command first and say what
  it does before running it.
