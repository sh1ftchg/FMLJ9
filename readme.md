# Forge Mod Loader Java 9 Interop
A mouthful, isn't it? That's why I called it "Shifty". This project brings bleeding edge java 9 to the minecraft modding masses.

## UPDATE: TODO/OTHER
- Make it easier for the plebian. Like, I made assumptions that people might not realize when I wrote the instructions: When you compile ShiftyClassLoader.java, java itself WILL look for it in the mod directory but under its package within that directory. Unless FML does something screwy to the DEFAULT java behavior... IE. <mod directory>/com/sh1ftchg/java9/ShiftyClassLoader<and its anonymous/named inners>.class
- Discovered that @LexManos is an [anti-intellectualist](https://gist.github.com/sh1ftchg/06425b5088521cd76390fae358c57d88)? Who knew?

## Instructions

Checkout the repo if you want... But really, all you need is ShiftyClassLoader.java. 

- Compile ShiftyClassLoader.java, use javac, or gcj, or whatever you like.
- Add the ".class" file's location to the java classpath. Alternatively, drop them in the game directory for the mod you want to run on Java 9.
- Add "-Djava.system.class.loader=com.sh1ftchg.java9.ShiftyClassLoader" without the quotes to your runtime arguments.
- Add "--add-opens java.base/jdk.internal.reflect=ALL-UNNAMED" to the java virtual machine's arguments.
    - The add opens option is java 9 specific. It opens the jdk.internal.reflect (formerly sun.reflect) package to the mod-loader for use.

![Just another screenshot](https://raw.githubusercontent.com/sh1ftchg/FMLJ9/master/screenshot.png)
