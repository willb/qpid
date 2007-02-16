package org.apache.qpid.server.handler;

import org.apache.log4j.Logger;
import org.apache.qpid.AMQException;
import org.apache.qpid.framing.BasicGetBody;
import org.apache.qpid.framing.BasicGetEmptyBody;
import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.protocol.AMQMethodEvent;
import org.apache.qpid.server.AMQChannel;
import org.apache.qpid.server.protocol.AMQProtocolSession;
import org.apache.qpid.server.queue.AMQQueue;
import org.apache.qpid.server.state.AMQStateManager;
import org.apache.qpid.server.state.StateAwareMethodListener;
import org.apache.qpid.server.virtualhost.VirtualHost;

public class BasicGetMethodHandler implements StateAwareMethodListener<BasicGetBody>
{
    private static final Logger _log = Logger.getLogger(BasicGetMethodHandler.class);

    private static final BasicGetMethodHandler _instance = new BasicGetMethodHandler();

    public static BasicGetMethodHandler getInstance()
    {
        return _instance;
    }

    private BasicGetMethodHandler()
    {
    }

    public void methodReceived(AMQStateManager stateManager, AMQMethodEvent<BasicGetBody> evt) throws AMQException
    {
        AMQProtocolSession session = stateManager.getProtocolSession();

        BasicGetBody body = evt.getMethod();
        final int channelId = evt.getChannelId();
        VirtualHost vHost = session.getVirtualHost();

        AMQChannel channel = session.getChannel(channelId);
        if (channel == null)
        {
            throw body.getChannelNotFoundException(evt.getChannelId());
        }
        else
        {
            AMQQueue queue = body.queue == null ? channel.getDefaultQueue() : vHost.getQueueRegistry().getQueue(body.queue);

            if (queue == null)
            {
                _log.info("No queue for '" + body.queue + "'");
                if(body.queue!=null)
                {
                    throw body.getConnectionException(AMQConstant.NOT_FOUND,
                                                      "No such queue, '" + body.queue + "'");
                }
                else
                {
                    throw body.getConnectionException(AMQConstant.NOT_ALLOWED,
                                                      "No queue name provided, no default queue defined.");
                }
            }
            else
            {
                if(!queue.performGet(session, channel, !body.noAck))
                {


                    // TODO - set clusterId
                    session.writeFrame(BasicGetEmptyBody.createAMQFrame(channelId, body.getMajor(), body.getMinor(), null));
                }
            }
        }
    }
}
