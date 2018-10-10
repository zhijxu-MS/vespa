// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

package com.yahoo.vespa.hosted.node.admin.task.util.file;

import com.yahoo.vespa.test.file.TestFileSystem;
import org.junit.Test;

import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * @author hakonhall
 */
public class UnixPathTest {
    private final FileSystem fs = TestFileSystem.create();

    @Test
    public void createParents() {
        Path parentDirectory = fs.getPath("/a/b/c");
        Path filePath = parentDirectory.resolve("bar");
        UnixPath path = new UnixPath(filePath);

        assertFalse(Files.exists(fs.getPath("/a")));
        path.createParents();
        assertTrue(Files.exists(parentDirectory));
    }

    @Test
    public void utf8File() {
        String original = "foo\nbar\n";
        UnixPath path = new UnixPath(fs.getPath("example.txt"));
        path.writeUtf8File(original);
        String fromFile = path.readUtf8File();
        assertEquals(original, fromFile);
    }

    @Test
    public void permissions() {
        String expectedPermissions = "rwxr-x---";
        UnixPath path = new UnixPath(fs.getPath("file.txt"));
        path.writeUtf8File("foo");
        path.setPermissions(expectedPermissions);
        assertEquals(expectedPermissions, path.getPermissions());
    }

    @Test(expected = IllegalArgumentException.class)
    public void badPermissionsString() {
        new UnixPath(fs.getPath("file.txt")).setPermissions("abcdefghi");
    }

    @Test
    public void owner() {
        Path path = fs.getPath("file.txt");
        UnixPath unixPath = new UnixPath(path);
        unixPath.writeUtf8File("foo");

        unixPath.setOwner("owner");
        assertEquals("owner", unixPath.getOwner());

        unixPath.setGroup("group");
        assertEquals("group", unixPath.getGroup());
    }

    @Test
    public void createDirectoryWithPermissions() {
        Path path = fs.getPath("dir");
        UnixPath unixPath = new UnixPath(path);
        String permissions = "rwxr-xr--";
        unixPath.createDirectory(permissions);
        assertTrue(unixPath.isDirectory());
        assertEquals(permissions, unixPath.getPermissions());
    }
}
