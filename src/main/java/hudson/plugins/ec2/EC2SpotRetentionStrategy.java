package hudson.plugins.ec2;

import java.util.logging.Logger;

public class EC2SpotRetentionStrategy extends EC2RetentionStrategy
{

	public EC2SpotRetentionStrategy(final String idleTerminationMinutes)
	{
		super(idleTerminationMinutes);
	}

	/**
	 * Try to connect to it ASAP
	 */
	@Override
	public void start(final EC2Computer c)
	{
		LOGGER.info("Start requested for " + c.getName());
		c.connect(false);
	}

	private static final Logger LOGGER = Logger.getLogger(EC2SpotRetentionStrategy.class.getName());
}
