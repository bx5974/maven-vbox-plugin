package com.alexecollins.maven.plugins.vbox;

import com.alexecollins.maven.plugins.vbox.provisioning.Provisioning;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.ArrayUtils;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.handler.ResourceHandler;
import org.mortbay.resource.Resource;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * @author alexec (alex.e.c@gmail.com)
 * @goal provision
 * @phase pre-integration-test
 */
public class ProvisionMojo extends AbstractVBoxesMojo {
	private final int port = 10350;
	private final Server server = new Server(port);

	/**
	 * Which targets to do, or all if "*".
	 *
	 * @parameter expression="${vbox.provision.targets}", default="*"
	 */
	protected String targets = "*";

	@Override
	protected void execute(VBox box) throws Exception {

		final Snapshot snapshot = Snapshot.POST_PROVISIONING;
		if (box.exists() && box.getSnapshots().contains(snapshot.toString())) {
			box.restoreSnapshot(Snapshot.POST_PROVISIONING);
			return;
		}

		getLog().info("provisioning '" + box.getName() + "'");
		if (!box.getProperties().getProperty("VMState").equals("running")) {
			box.start();
		}

		for (String f : box.getManifest().getFile()) {
			final File d = new File(box.getTarget(), f);
			if (!d.exists())
				FileUtils.copyURLToFile(new URL(box.getSrc() + "/" + f), d);
		}

		startServer(box);
		try {
			final List<String> allowedTargets = Arrays.asList(this.targets.split(","));

			final List<Provisioning.Target> targets = box.getProvisioning().getTarget();
			for (Provisioning.Target stage : targets) {
				if (allowedTargets.contains(stage.getName()) || allowedTargets.equals(Collections.singletonList("*"))) {
					getLog().info("executing target " + stage.getName());
					executeTarget(box, stage);
					if (stage.equals(targets.get(targets.size() - 1))) {
						box.pressPowerButton();
						box.awaitPowerOff(10000);
						box.takeSnapshot(snapshot);
					}
				} else {
					getLog().info("skipping target " + stage.getName());
				}
			}
		} finally {
			stopServer();
		}
	}

	private void executeTarget(final VBox box, final Provisioning.Target stage) throws IOException, InterruptedException, TimeoutException {
		for (Object o : stage.getPortForwardOrAwaitPortOrKeyboardPutScanCodes()) {
			if (o instanceof Provisioning.Target.PortForward)
				portForward(box.getName(), (Provisioning.Target.PortForward) o);
			else if (o instanceof Provisioning.Target.KeyboardPutScanCodes)
				keyboardPutScanCodes(box.getName(), ((Provisioning.Target.KeyboardPutScanCodes) o));
			else if (o instanceof Provisioning.Target.Sleep) {
				final Provisioning.Target.Sleep s = (Provisioning.Target.Sleep) o;
				final long seconds = s.getMinutes() * 60 + s.getSeconds();

				getLog().info("sleeping for " + seconds + " second(s)");
				Thread.sleep(seconds * 1000);
			} else if (o instanceof Provisioning.Target.Exec) {
				ExecUtils.exec(formatConfig(box.getName(), ((Provisioning.Target.Exec) o).getValue()));
			} else if (o instanceof Provisioning.Target.AwaitPort) {
				awaitPort((Provisioning.Target.AwaitPort) o);
			} else
				throw new AssertionError("unexpected provision");
		}
	}

	private void awaitPort(final Provisioning.Target.AwaitPort ap) throws IOException, TimeoutException, InterruptedException {
		final long start = System.currentTimeMillis();

		while (true) {
			getLog().info("awaiting port localhost:" + ap.getHostport());
			try {
				new Socket("localhost", ap.getHostport()).close();
				return;
			} catch (ConnectException e) {
				// nop
			}

			if (System.currentTimeMillis() > start + ap.getTimeout()) {
				throw new TimeoutException("timed out waiting for port localhost:" + ap.getHostport());
			}

			Thread.sleep(30000);
		}
	}

	void stopServer() throws Exception {
		if (server.isRunning()) {
			getLog().info("stopping server");
			server.stop();
		}
	}

	void startServer(VBox box) throws Exception {
		getLog().info("starting server on port " + port);

		final ResourceHandler rh = new ResourceHandler();
		final File resource = new File("target/vbox/boxes/" + box.getName());
		getLog().debug("resource " + resource);
		assert resource.exists();
		rh.setBaseResource(Resource.newResource(resource.toURI().toURL()));
		server.setHandler(rh);
		server.start();

		final URL u = new URL("http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port);
		getLog().info("testing server by getting " + u);
		final HttpURLConnection c = (HttpURLConnection) u.openConnection();
		c.connect();
		if (403 != c.getResponseCode()) throw new IllegalStateException(c.getResponseMessage());
		c.disconnect();

	}

	public void keyboardPutScanCodes(String name, Provisioning.Target.KeyboardPutScanCodes ksc) throws IOException, InterruptedException {

		{
			final String keys = ksc.getKeys();
			if (keys != null) {
				getLog().info("typing keys " + keys);
				final List<Integer> sc = new ArrayList<Integer>();
				for (String key : keys.split(",")) {
					for (int c : ScanCodes.forKey(key)) {
						sc.add(c);
					}
				}
				keyboardPutScanCodes(name, ArrayUtils.toPrimitive(sc.toArray(new Integer[sc.size()])));
			}
		}
		{
			String line;
			line = ksc.getLine();
			if (line != null) {
				line = formatConfig(name, line);

				getLog().info("typing line '" + line + "'");

				keyboardPutScanCodes(name, ArrayUtils.addAll(ScanCodes.forString(line), ScanCodes.forKey("Enter")));
			}
		}

		{
			String text = ksc.getValue();
			if (text != null && text.length() > 0) {
				text = formatConfig(name, text);

				getLog().info("typing text '" + text + "'");

				keyboardPutScanCodes(name, ArrayUtils.addAll(ScanCodes.forString(text), ScanCodes.forKey("Enter")));
			}
		}
	}

	public String formatConfig(final String name, String line) throws IOException, InterruptedException {
		line = line.replaceAll("%IP%", InetAddress.getLocalHost().getHostAddress());
		line = line.replaceAll("%PORT%", String.valueOf(port));
		line = line.replaceAll("%VBOX_ADDITIONS%", VBox.findGuestAdditions().getPath().replaceAll("\\\\", "/"));
		line = line.replaceAll("%NAME%", name);
		return line;
	}

	private void keyboardPutScanCodes(String name, int[] scancodes) throws IOException, InterruptedException {
		getLog().debug("typing " + Arrays.toString(scancodes));

		while (scancodes.length > 0) {
			final List<String> command = new ArrayList<String>();
			command.addAll(Arrays.asList("vboxmanage", "controlvm", name, "keyboardputscancode"));

			int i = 0;
			for (int scancode : scancodes) {
				command.add((scancode > 0xf ? "" : "0") + Integer.toHexString(scancode));
				i++;
				// split on enter
				if (i >= 16 || scancode == 156) {
					break;
				}
			}
			ExecUtils.exec(command.toArray(new String[command.size()]));
			Thread.sleep(scancodes[i - 1] == 156 ? 2000 : 100); //  a short sleep to let the OS digest
			scancodes = ArrayUtils.subarray(scancodes, i, scancodes.length);
		}
	}

	private void portForward(String name, Provisioning.Target.PortForward pf) throws IOException, InterruptedException {
		final int hostPort = pf.getHostport();
		final int guestPort = pf.getGuestport();
		getLog().info("adding port forward hostport=" + hostPort + " guestport=" + guestPort);
		ExecUtils.exec("vboxmanage", "setextradata", name, "VBoxInternal/Devices/e1000/0/LUN#0/Config/" + guestPort + "/HostPort", String.valueOf(hostPort));
		ExecUtils.exec("vboxmanage", "setextradata", name, "VBoxInternal/Devices/e1000/0/LUN#0/Config/" + guestPort + "/GuestPort", String.valueOf(guestPort));
		ExecUtils.exec("vboxmanage", "setextradata", name, "VBoxInternal/Devices/e1000/0/LUN#0/Config/" + guestPort + "/Protocol", "TCP");
	}
}