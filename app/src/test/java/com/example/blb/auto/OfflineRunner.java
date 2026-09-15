package com.example.blb.auto;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** 用实际选择器和虚拟时钟跑流程，不访问设备或登录接口。 */
class OfflineRunner extends StepRunner {
    static final class Host implements StepRunner.Host {
        boolean cancelled;
        final List<String> logs = new ArrayList<>();

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void log(String message) { logs.add(message); }
        @Override public Decision awaitUser(String reason) { return Decision.ABORT; }
    }

    final Host testHost;
    final List<String> presses = new ArrayList<>();
    NodeView active = FakeNode.node();
    NodeView others;
    long now = 1_000;
    int backs;

    OfflineRunner() throws Exception {
        this(new Host());
    }

    OfflineRunner(Host host) throws Exception {
        super(bundled(), host);
        testHost = host;
    }

    static SelectorSet bundled() throws Exception {
        File file = new File("src/main/assets/selectors.json");
        if (!file.isFile()) file = new File("app/src/main/assets/selectors.json");
        return new SelectorSet(SelectorSet.parse(new String(
                Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)), "test assets");
    }

    @Override NodeView activeRoot() { return active; }
    @Override NodeView otherRoots() { return others; }
    @Override long elapsedRealtime() { return now; }
    @Override public void waitMillis(long ms) { now += ms; }
    @Override public void sleepHuman() { now += 500; }
    @Override public boolean isTargetForeground() { return true; }
    @Override public String activePackage() { return "com.sfacg"; }
    @Override public int windowCount() { return others == null ? 1 : 2; }

    @Override public Outcome waitForAny(long timeoutMs, String... keys) throws StepFailure {
        checkCancelled();
        Outcome result = findAny(keys);
        if (result == null) throw new StepFailure(Kind.TIMEOUT, "offline timeout");
        return result;
    }

    @Override public void clickNode(String label, NodeView node) throws StepFailure {
        checkCancelled();
        presses.add(label);
    }

    @Override public boolean pressOrLog(String label, NodeView node) throws StepFailure {
        clickNode(label, node);
        return true;
    }

    @Override public void back() throws StepFailure {
        checkCancelled();
        backs++;
    }

    @Override public void launchTarget(long timeoutMs) throws StepFailure { checkCancelled(); }
    @Override public void scrollToTop(int times) throws StepFailure { checkCancelled(); }

}
