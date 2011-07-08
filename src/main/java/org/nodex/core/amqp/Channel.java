package org.nodex.core.amqp;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import org.nodex.core.DoneHandler;
import org.nodex.core.Nodex;
import org.nodex.core.composition.Completion;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User: timfox
 * Date: 02/07/2011
 * Time: 07:25
 */
public class Channel {
  private com.rabbitmq.client.Channel channel;

  Channel(com.rabbitmq.client.Channel channel) {
    this.channel = channel;
  }

  public void publish(final String exchange, final String routingKey, AmqpProps props, final byte[] body) {
    try {
      if (props == null) {
        props = new AmqpProps();
      }
      AMQP.BasicProperties aprops = props.toBasicProperties();
      channel.basicPublish(exchange, routingKey, aprops, body);
    } catch (IOException e) {
      //TODO handle exception by passing them back on callback
      e.printStackTrace();
    }
  }

  public void publish(final String exchange, final String routingKey, final AmqpProps props, final String message) {
    try {
      publish(exchange, routingKey, props, message.getBytes("UTF-8"));
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
    }
  }

  public void declareQueue(final String queueName, final boolean durable, final boolean exclusive, final boolean autoDelete,
                           final DoneHandler doneCallback) {
    Nodex.instance.executeInBackground(new Runnable() {
      public void run() {
        try {
          channel.queueDeclare(queueName, durable, exclusive, autoDelete, null);
          doneCallback.onDone();
        } catch (IOException e) {
          //TODO handle exception by passing them back on callback
          e.printStackTrace();
        }
      }
    });
  }

  public void subscribe(final String queueName, final boolean autoAck, final AmqpMsgCallback messageCallback) {
    Nodex.instance.executeInBackground(new Runnable() {
      public void run() {
        try {
          channel.basicConsume(queueName, autoAck, "blah",
              new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag,
                                           Envelope envelope,
                                           AMQP.BasicProperties properties,
                                           byte[] body)
                    throws IOException {
                  AmqpProps props = properties == null ? null : new AmqpProps(properties);
                  messageCallback.onMessage(props, body);
                }
              });
        } catch (IOException e) {
          //TODO handle exception by passing them back on callback
          e.printStackTrace();
        }
      }
    });
  }

  private Map<String, AmqpMsgCallback> callbacks = new ConcurrentHashMap<String, AmqpMsgCallback>();
  private volatile String responseQueue;
  private Completion responseQueueSetup = new Completion();

  private synchronized void createResponseQueue() {
    if (responseQueue == null) {
      final String queueName = UUID.randomUUID().toString();
      declareQueue(queueName, false, true, true, new DoneHandler() {
        public void onDone() {
          responseQueue = queueName;
          responseQueueSetup.complete(); //Queue is now set up
          subscribe(queueName, true, new AmqpMsgCallback() {
            public void onMessage(AmqpProps props, byte[] body) {
              String cid = props.correlationId;
              if (cid == null) {
                //TODO better error reporting
                System.err.println("No correlation id");
              } else {
                AmqpMsgCallback cb = callbacks.get(cid);
                if (cb == null) {
                  System.err.println("No callback for correlation id");
                } else {
                  cb.onMessage(props, body);
                }
              }
            }
          });
        }
      });
    }
  }

  // Request-response pattern

  public Completion request(final String exchange, final String routingKey, final AmqpProps props, final String body, final AmqpMsgCallback responseCallback) {
    try {
      return request(exchange, routingKey, props, body == null ? null : body.getBytes("UTF-8"), responseCallback);
    } catch (UnsupportedEncodingException e) {
      e.printStackTrace();
      return null;
    }
  }

  public Completion request(final String exchange, final String routingKey, final AmqpProps props, final byte[] body, final AmqpMsgCallback responseCallback) {
    final AmqpProps theProps = props == null ? new AmqpProps() : props;
    if (responseQueue == null) createResponseQueue();
    //We make sure we don't actually send until the response queue has been setup, this is done by using a
    //Completion
    final Completion c = new Completion();
    responseQueueSetup.onComplete(new DoneHandler() {
      public void onDone() {
        AmqpMsgCallback cb = new AmqpMsgCallback() {
          public void onMessage(AmqpProps props, byte[] body) {
            responseCallback.onMessage(props, body);
            c.complete();
          }
        };
        String cid = UUID.randomUUID().toString();
        theProps.correlationId = cid;
        theProps.replyTo = responseQueue;
        callbacks.put(cid, cb);
        publish(exchange, routingKey, theProps, body);
      }
    });
    return c;
  }


  public void close(final DoneHandler doneCallback) {
    Nodex.instance.executeInBackground(new Runnable() {
      public void run() {
        try {
          channel.close();
          doneCallback.onDone();
        } catch (IOException e) {
          //TODO handle exception by passing them back on callback
          e.printStackTrace();
        }
      }
    });
  }


}