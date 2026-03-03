package io.q1.core.io;

import java.io.IOException;
import java.nio.file.Path;

/** Creates {@link NioFileIO} instances. Stateless; safe to share. */
public final class NioFileIOFactory implements FileIOFactory {

    public static final NioFileIOFactory INSTANCE = new NioFileIOFactory();

    @Override
    public FileIO open(Path path) throws IOException {
        return new NioFileIO(path);
    }
}
