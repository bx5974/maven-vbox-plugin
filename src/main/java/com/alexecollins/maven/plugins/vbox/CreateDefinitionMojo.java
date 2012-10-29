package com.alexecollins.maven.plugins.vbox;

import com.alexecollins.maven.plugins.vbox.schema.VirtualBox;
import org.apache.commons.io.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * @author alexec (alex.e.c@gmail.com)
 * @goal create-definition
 */
public class CreateDefinitionMojo extends AbstractVBoxMojo {

	/**
	 * @parameter expression="${vbox.name}"
	 * @required
	 */
	protected String name;

	public void execute() throws MojoExecutionException, MojoFailureException {
		try {
			createDefn(new File("src/main/vbox/" + name));
		} catch (Exception e) {
			throw new MojoExecutionException("failed to create definition", e);
		}
	}

	void createDefn(File sink) throws IOException, URISyntaxException {

		if (!sink.exists() && !sink.mkdir()) throw new IllegalStateException();

		final URL u = getClass().getResource("/" + name);
		final VirtualBox cfg = getVirtualBox(u.toURI());

		for (String f : new String[]{"VirtualBox.xml", "Manifest.xml", "Provisions.xml"}) {
			FileUtils.copyURLToFile(getClass().getResource("/" + name + "/" + f), new File(sink, f));
		}

		for (String f : getManifest(u.toURI()).getFile()) {
			FileUtils.copyURLToFile(getClass().getResource("/" + name + "/" + f), new File(sink, f));
		}
	}

}