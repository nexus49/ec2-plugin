package hudson.plugins.ec2.win;

import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.plugins.ec2.EC2Computer;
import hudson.plugins.ec2.EC2SpotComputerLauncher;
import hudson.plugins.ec2.win.winrm.WindowsProcess;
import hudson.remoting.Channel;
import hudson.remoting.Channel.Listener;
import hudson.slaves.ComputerLauncher;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.Instance;

public class EC2SpotWindowsLauncher extends EC2SpotComputerLauncher
{

    final long sleepBetweenAttemps = TimeUnit.SECONDS.toMillis(10);

    @Override
    protected void launch(final EC2Computer computer, final PrintStream logger, final Instance inst) throws IOException, AmazonClientException,
    InterruptedException {
        final WinConnection connection = connectToWinRM(computer, logger);

        try {
            final String initScript = computer.getNode().initScript;
            final String tmpDir = (computer.getNode().tmpDir != null ? computer.getNode().tmpDir : "C:\\Windows\\Temp\\");

            logger.println("Creating tmp directory if it does not exist");
            connection.execute("if not exist " + tmpDir + " mkdir " + tmpDir);

            if(initScript!=null && initScript.trim().length()>0 && !connection.exists(tmpDir + ".jenkins-init")) {
                logger.println("Executing init script");
                final OutputStream init = connection.putFile(tmpDir + "init.bat");
                init.write(initScript.getBytes("utf-8"));

                final WindowsProcess initProcess = connection.execute("cmd /c " + tmpDir + "init.bat");
                IOUtils.copy(initProcess.getStdout(),logger);

                final int exitStatus = initProcess.waitFor();
                if (exitStatus!=0) {
                    logger.println("init script failed: exit code="+exitStatus);
                    return;
                }

                final OutputStream initGuard = connection.putFile(tmpDir + ".jenkins-init");
                initGuard.write("init ran".getBytes());
                logger.println("init script failed ran successfully");
            }


            final OutputStream slaveJar = connection.putFile(tmpDir + "slave.jar");
            slaveJar.write(Hudson.getInstance().getJnlpJars("slave.jar").readFully());

            logger.println("slave.jar sent remotely. Bootstrapping it");

            final String jvmopts = computer.getNode().jvmopts;
            final WindowsProcess process = connection.execute("java " + (jvmopts != null ? jvmopts : "") + " -jar " + tmpDir + "slave.jar", 86400);
            computer.setChannel(process.getStdout(), process.getStdin(), logger, new Listener() {
                @Override
                public void onClosed(final Channel channel, final IOException cause) {
                    process.destroy();
                    connection.close();
                }
            });
        } catch (final Throwable ioe) {
            logger.println("Ouch:");
            ioe.printStackTrace(logger);
        } finally {
            connection.close();
        }
    }

    private WinConnection connectToWinRM(final EC2Computer computer, final PrintStream logger) throws AmazonClientException,
    InterruptedException {
        final long timeout = computer.getNode().getLaunchTimeoutInMillis();
        final long startTime = System.currentTimeMillis();

        logger.println(computer.getNode().getDisplayName() + " booted at " + computer.getNode().getCreatedTime());
        boolean alreadyBooted = (startTime - computer.getNode().getCreatedTime()) > TimeUnit.MINUTES.toMillis(3);
        while (true) {
            try {
                final long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > timeout) {
                    throw new AmazonClientException("Timed out after " + (waitTime / 1000)
                            + " seconds of waiting for winrm to be connected");
                }
                final Instance instance = computer.updateInstanceDescription();
                final String vpc_id = instance.getVpcId();
                String ip, host;

                if (computer.getNode().usePrivateDnsName) {
                    host = instance.getPrivateDnsName();
                    ip = instance.getPrivateIpAddress(); // SmbFile doesn't quite work with hostnames
                } else {
                    host = instance.getPublicDnsName();
                    if (host == null || host.equals("")) {
                        host = instance.getPrivateDnsName();
                        ip = instance.getPrivateIpAddress(); // SmbFile doesn't quite work with hostnames
                    }
                    else {
                        host = instance.getPublicDnsName();
                        ip = instance.getPublicIpAddress(); // SmbFile doesn't quite work with hostnames
                    }
                }

                if ("0.0.0.0".equals(host)) {
                    logger.println("Invalid host 0.0.0.0, your host is most likely waiting for an ip address.");
                    throw new IOException("goto sleep");
                }

                logger.println("Connecting to " + host + "(" + ip + ") with WinRM as " + computer.getNode().remoteAdmin);

                final WinConnection connection = new WinConnection(ip, computer.getNode().remoteAdmin, computer.getNode().getAdminPassword());
                connection.setUseHTTPS(computer.getNode().isUseHTTPS());
                if (!connection.ping()) {
                    logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                    Thread.sleep(sleepBetweenAttemps);
                    continue;
                }

                if (!alreadyBooted || computer.getNode().stopOnTerminate) {
                    logger.println("WinRM service responded. Waiting for WinRM service to stabilize on " + computer.getNode().getDisplayName());
                    Thread.sleep(computer.getNode().getBootDelay());
                    alreadyBooted = true;
                    logger.println("WinRM should now be ok on " + computer.getNode().getDisplayName());
                    if (!connection.ping()) {
                        logger.println("WinRM not yet up. Sleeping 10s.");
                        Thread.sleep(sleepBetweenAttemps);
                        continue;
                    }
                }

                logger.println("Connected with WinRM.");
                return connection; // successfully connected
            } catch (final IOException e) {
                logger.println("Waiting for WinRM to come up. Sleeping 10s.");
                Thread.sleep(sleepBetweenAttemps);
            }
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
