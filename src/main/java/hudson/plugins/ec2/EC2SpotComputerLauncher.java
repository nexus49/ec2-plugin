package hudson.plugins.ec2;

import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.StartInstancesRequest;
import com.amazonaws.services.ec2.model.StartInstancesResult;

public abstract class EC2SpotComputerLauncher extends ComputerLauncher
{

	private static final Logger LOGGER = Logger.getLogger(EC2ComputerLauncher.class.getName());

	@Override
	public void launch(final SlaveComputer _computer, final TaskListener listener)
	{
		try
		{
			final EC2Computer computer = (EC2Computer) _computer;
			final PrintStream logger = listener.getLogger();

			String msg = null;
			long timeToWait = 5000L;

			OUTER: while (true)
			{
				final String baseMsg = "Node " + computer.getName();
				if (computer.getNode() instanceof EC2SpotSlave)
				{
					EC2SpotSlave ec2Slave = (EC2SpotSlave) computer.getNode();
					if (ec2Slave.isSpotRequestDead(computer))
					{
						// Terminate launch
						return;
					}

					computer.updateInstanceIdFromSpotRequest();
					timeToWait = StringUtils.isBlank(computer.getInstanceId()) ? 10000L : 5000L;
					msg = baseMsg + " SpotRequest is still requesting the instance, waiting 10s";
				}

				if (StringUtils.isNotBlank(computer.getInstanceId()))
				{
					switch (computer.getState())
					{
						case PENDING:
							msg = baseMsg + " is still pending/launching, waiting 5s";
							break;
						case STOPPING:
							msg = baseMsg + " is still stopping, waiting 5s";
							break;
						case RUNNING:
							msg = baseMsg + " is ready";
							LOGGER.finer(msg);
							logger.println(msg);
							break OUTER;
						case STOPPED:
							msg = baseMsg + " is stopped, sending start request";
							LOGGER.finer(msg);
							logger.println(msg);

							final AmazonEC2 ec2 = computer.getCloud().connect();
							final List<String> instances = new ArrayList<String>();
							instances.add(computer.getInstanceId());

							final StartInstancesRequest siRequest = new StartInstancesRequest(instances);
							final StartInstancesResult siResult = ec2.startInstances(siRequest);

							msg = baseMsg + ": sent start request, result: " + siResult;
							LOGGER.finer(baseMsg);
							logger.println(baseMsg);
							continue OUTER;
						case SHUTTING_DOWN:
						case TERMINATED:
							// abort
							msg = baseMsg + " is terminated or terminating, aborting launch";
							LOGGER.info(msg);
							logger.println(msg);
							return;
						default:
							msg = baseMsg + " is in an unknown state, retrying in 5s";
							break;
					}
				}

				// check every X secs
				Thread.sleep(timeToWait);
				// and report to system log and console
				LOGGER.finest(msg);
				logger.println(msg);
			}

			launch(computer, logger, computer.describeInstance());
		}
		catch (final AmazonClientException e)
		{
			e.printStackTrace(listener.error(e.getMessage()));
		}
		catch (final IOException e)
		{
			e.printStackTrace(listener.error(e.getMessage()));
		}
		catch (final InterruptedException e)
		{
			e.printStackTrace(listener.error(e.getMessage()));
		}

	}

	/**
	 * Stage 2 of the launch. Called after the EC2 instance comes up.
	 */
	protected abstract void launch(EC2Computer computer, PrintStream logger, Instance inst) throws AmazonClientException,
			IOException, InterruptedException;
}
