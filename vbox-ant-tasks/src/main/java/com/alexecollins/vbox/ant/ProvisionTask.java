package com.alexecollins.vbox.ant;

import com.alexecollins.vbox.core.VBox;
import com.alexecollins.vbox.core.task.Provision;
import org.apache.tools.ant.BuildException;

import java.util.Collections;

/**
 * @author alexec (alex.e.c@gmail.com)
 */
public class ProvisionTask extends AbstractTask{

	@Override
	public void execute() throws BuildException {
		if (dir == null) {
			throw new BuildException("dir is null");
		}
		try {
			new Provision(work(), new VBox(context(), dir.toURI()), Collections.<String>singleton("*")).call();
		} catch (Exception e) {
			throw new BuildException(e);
		}
	}
}
