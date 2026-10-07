// WaeIIFace.aidl
package com.wax.module.xposed.bridge;

import android.os.ParcelFileDescriptor;
import java.util.List;

// Declare any non-default types here with import statements

interface WaeIIFace {
    /**
     * Demonstrates some basic types that you can use as parameters
     * and return values in AIDL.
     */
    ParcelFileDescriptor openFile(String path, boolean create);

    boolean createDir(String path);

    List<String> listFiles(String path);

    boolean exists(String path);

}
