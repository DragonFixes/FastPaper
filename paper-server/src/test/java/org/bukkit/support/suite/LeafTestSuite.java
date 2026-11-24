package org.bukkit.support.suite;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;
import org.junit.platform.suite.api.SuiteDisplayName;

@Suite(failIfNoTests = false)
@SuiteDisplayName("Test suite for tests used by Leaf")
@SelectPackages({
    "net.minecraft",
    "gg.pufferfish.pufferfish"
})
@IncludeTags("LeafTest")
@ConfigurationParameter(key = "TestSuite", value = "LeafTest")
public class LeafTestSuite {
}
