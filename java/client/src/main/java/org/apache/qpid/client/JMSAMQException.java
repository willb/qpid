/*
 *
 * Copyright (c) 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.apache.qpid.client;

import org.apache.qpid.AMQException;

import javax.jms.JMSException;

/**
 * @author Apache Software Foundation
 */
public class JMSAMQException extends JMSException
{
    public JMSAMQException(AMQException s)
    {
        super(s.getMessage(), String.valueOf(s.getErrorCode()));
        setLinkedException(s);
    }
}
