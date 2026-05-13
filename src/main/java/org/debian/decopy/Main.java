package org.debian.decopy;

/**
 * Entry point for the decopy tool.
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        Options options = Options.parse(args);
        System.exit(Decopy.run(options));
    }
}
