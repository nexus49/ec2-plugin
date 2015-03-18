package hudson.plugins.ec2;

public enum SpotInstanceRequestState
{
	OPEN, ACTIVE, CLOSED, CANCELLED, FAILED;

	public String getCode()
	{
		return name().toLowerCase();
	}
}
