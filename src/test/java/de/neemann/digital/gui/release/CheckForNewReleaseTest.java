/*
 * Copyright (c) 2026
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.release;

import junit.framework.TestCase;

public class CheckForNewReleaseTest extends TestCase {

    public void testInstalledReleaseDoesNotPrompt() {
        assertTrue(CheckForNewRelease.isRunningVersionCurrentOrNewer("v1.2.0", "v1.2.0"));
    }

    public void testDevelopmentBuildBasedOnLatestReleaseDoesNotPrompt() {
        assertTrue(CheckForNewRelease.isRunningVersionCurrentOrNewer("v1.2.0-10-g1234567", "v1.2.0"));
    }

    public void testDevelopmentBuildBasedOnRollingReleaseDoesNotPrompt() {
        assertTrue(CheckForNewRelease.isRunningVersionCurrentOrNewer("release-10-g1234567", "release"));
    }

    public void testOlderReleasePrompts() {
        assertFalse(CheckForNewRelease.isRunningVersionCurrentOrNewer("v1.1.0", "v1.2.0"));
    }

    public void testGitHubCommitComparison() {
        assertTrue(CheckForNewRelease.isRunningVersionCurrentOrNewer("release-10-g1234567", "v1.2.0", "ahead"));
        assertTrue(CheckForNewRelease.isRunningVersionCurrentOrNewer("v1.2.0", "v1.2.0", "identical"));
        assertFalse(CheckForNewRelease.isRunningVersionCurrentOrNewer("v1.1.0", "v1.2.0", "behind"));
        assertFalse(CheckForNewRelease.isRunningVersionCurrentOrNewer("release-10-g1234567", "v1.2.0", "diverged"));
    }

    public void testExtractCommitFromGitDescribeRevision() {
        assertEquals("1234567", CheckForNewRelease.getCommitFromRevision("release-10-g1234567"));
        assertEquals("1234567", CheckForNewRelease.getCommitFromRevision("v1.2.0-2-g1234567-dirty"));
        assertEquals("unknown", CheckForNewRelease.getCommitFromRevision("v1.2.0"));
    }

}
