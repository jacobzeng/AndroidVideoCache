package com.danikula.videocache.sample;

import android.app.Application;
import android.content.Context;

import com.danikula.videocache.HttpProxyCacheServer;
import com.danikula.videocache.file.FileNameGenerator;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class App extends Application {

    private HttpProxyCacheServer proxy;

    public static HttpProxyCacheServer getProxy(Context context) {
        App app = (App) context.getApplicationContext();
        return app.proxy == null ? (app.proxy = app.newProxy()) : app.proxy;
    }

    private HttpProxyCacheServer newProxy() {
        return new HttpProxyCacheServer.Builder(this)
                .fileNameGenerator(new FileNameGenerator() {
                    @Override
                    public String generate(String url) {
                        return resolveFileNameFromUrl(url);
                    }
                })
                .maxCacheFilesCount(999)
//                .maxCacheSize(1024 * 1024 * 1024)
                .cacheDirectory(Utils.getVideoCacheDir(this))
                .build();
    }

    public static String resolveFileNameFromUrl(String url) {
        int slashIndex = url.lastIndexOf('/');
        String fileName = url.substring(slashIndex, url.length());
        try {
            return URLDecoder.decode(fileName, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return fileName;
        }
    }
}
