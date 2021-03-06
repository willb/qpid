/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.management.ui.views.logging;

import org.eclipse.jface.viewers.IStructuredContentProvider;
import org.eclipse.jface.viewers.Viewer;

import javax.management.openmbean.TabularDataSupport;
import java.util.Collection;

/**
 * Content Provider class for theLogging Management table viewers
 */
public class LoggingTableContentProvider  implements IStructuredContentProvider
{
    
    public void inputChanged(Viewer v, Object oldInput, Object newInput)
    {
        
    }
    
    public void dispose()
    {
        
    }
    
    public Object[] getElements(Object parent)
    {
        Collection<Object> rowCollection = ((TabularDataSupport) parent).values();
       
        return rowCollection.toArray();
    }
}