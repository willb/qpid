/*
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
 */

%module cqpid
%include "std_string.i"
%include "../../swig_python_typemaps.i"

/* Needed for get/setPriority methods.  Surprising SWIG 1.3.40 doesn't
 * convert uint8_t by default. */
%apply unsigned char { uint8_t };


/*
 * Exceptions
 *
 * The convention below is that exceptions in _cqpid.so have the same
 * names as in the C++ library.  They get renamed to their Python
 * equivalents when brought into the Python wrapping
 */
%{
static PyObject* pNoMessageAvailable;
static PyObject* pTargetCapacityExceeded;
static PyObject* pNotFound;
static PyObject* pTransportFailure;
%}

%init %{
    pNoMessageAvailable = PyErr_NewException(
        "_cqpid.NoMessageAvailable", NULL, NULL);
    Py_INCREF(pNoMessageAvailable);
    PyModule_AddObject(m, "NoMessageAvailable", pNoMessageAvailable);

    pTargetCapacityExceeded = PyErr_NewException(
        "_cqpid.TargetCapacityExceeded", NULL, NULL);
    Py_INCREF(pTargetCapacityExceeded);
    PyModule_AddObject(m, "TargetCapacityExceeded", pTargetCapacityExceeded);

    pNotFound = PyErr_NewException(
        "_cqpid.NotFound", NULL, NULL);
    Py_INCREF(pNotFound);
    PyModule_AddObject(m, "NotFound", pNotFound);

    pTransportFailure = PyErr_NewException(
        "_cqpid.TransportFailure", NULL, NULL);
    Py_INCREF(pTransportFailure);
    PyModule_AddObject(m, "TransportFailure", pTransportFailure);
%}

%pythoncode %{
    Empty = _cqpid.NoMessageAvailable
    TargetCapacityExceeded = _cqpid.TargetCapacityExceeded
    NotFound = _cqpid.NotFound
    ConnectError = _cqpid.TransportFailure
%}

/* Define the general-purpose exception handling */
%exception {
    PyObject * pExceptionType = NULL;
    std::string error;
    Py_BEGIN_ALLOW_THREADS;
    try {
        $action
    } catch (qpid::messaging::NoMessageAvailable & ex) {
        pExceptionType = pNoMessageAvailable;
        error = ex.what();
    } catch (qpid::messaging::TargetCapacityExceeded & ex) {
        pExceptionType = pTargetCapacityExceeded;
        error = ex.what();
    } catch (qpid::messaging::NotFound & ex) {
        pExceptionType = pNotFound;
        error = ex.what();
    } catch (qpid::messaging::TransportFailure & ex) {
        pExceptionType = pTransportFailure;
        error = ex.what();
    } catch (qpid::types::Exception& ex) {
        pExceptionType = PyExc_RuntimeError;
        error = ex.what();
    }
    Py_END_ALLOW_THREADS;
    if (!error.empty()) {
        PyErr_SetString(pExceptionType, error.c_str());
        return NULL;
    }
}


/* This only renames the non-const version (I believe).  Then again, I
 * don't even know why there is a non-const version of the method. */
%rename(opened) qpid::messaging::Connection::isOpen();
%rename(receiver) qpid::messaging::Session::createReceiver;
%rename(sender) qpid::messaging::Session::createSender;
%rename(_acknowledge_all) qpid::messaging::Session::acknowledge(bool);
%rename(_acknowledge_msg) qpid::messaging::Session::acknowledge(
    Message &, bool);

%rename(_fetch) qpid::messaging::Receiver::fetch;
%rename(unsettled) qpid::messaging::Receiver::getUnsettled;
%rename(available) qpid::messaging::Receiver::getAvailable;

%rename(unsettled) qpid::messaging::Sender::getUnsettled;
%rename(available) qpid::messaging::Sender::getAvailable;
%rename(_send) qpid::messaging::Sender::send;

%rename(_getReplyTo) qpid::messaging::Message::getReplyTo;
%rename(_setReplyTo) qpid::messaging::Message::setReplyTo;
%rename(_getTtl) qpid::messaging::Message::getTtl;
%rename(_setTtl) qpid::messaging::Message::setTtl;


%include "../qpid.i"

%extend qpid::messaging::Connection {
    %pythoncode %{
         # Handle the different options by converting underscores to hyphens.
         # Also, the sasl_mechanisms option in Python has no direct
         # equivalent in C++, so we will translate them to sasl_mechanism
         # when possible.
         def __init__(self, url=None, **options):
             args = [url] if url else []
             if options :
                 if "sasl_mechanisms" in options :
                     if ' ' in options.get("sasl_mechanisms",'') :
                         raise Exception(
                             "C++ Connection objects are unable to handle "
                             "multiple sasl-mechanisms")
                     options["sasl_mechanism"] = options.pop("sasl_mechanisms")
                 args.append(options)
             this = _cqpid.new_Connection(*args)
             try: self.this.append(this)
             except: self.this = this
    %}

    /* Return a pre-existing session with the given name, if one
     * exists, otherwise return a new one.  (Note that if a
     * pre-existing session exists, the transactional argument is
     * ignored, and the returned session might not satisfy the desired
     * setting. */
    qpid::messaging::Session _session(const std::string & name,
                                     bool transactional) {
        if (!name.empty()) {
            try {
                return self->getSession(name);
            }
            catch (const qpid::messaging::KeyError &) {
            }
        }
        if (transactional) {
            return self->createTransactionalSession(name);
        }
        else {
            return self->createSession(name);
        }
    }

    %pythoncode %{
        def session(self, name=None, transactional=False) :
            if name is None :
                name = ''
            return self._session(name, transactional)
    %}

    %pythoncode %{
        @staticmethod
        def establish(url=None, **options) :
            conn = Connection(url, **options)
            conn.open()
            return conn
    %}
}

%extend qpid::messaging::Session {
    %pythoncode %{
         def acknowledge(self, message=None, disposition=None, sync=True) :
             if disposition :
                 raise Exception("SWIG does not support dispositions yet. Use "
                                 "Session.reject and Session.release instead")
             if message :
                 self._acknowledge_msg(message, sync)
             else :
                 self._acknowledge_all(sync)

         __swig_getmethods__["connection"] = getConnection
         if _newclass: connection = _swig_property(getConnection)
    %}
}


%extend qpid::messaging::Receiver {
    %pythoncode %{
         __swig_getmethods__["capacity"] = getCapacity
         __swig_setmethods__["capacity"] = setCapacity
         if _newclass: capacity = _swig_property(getCapacity, setCapacity)

         __swig_getmethods__["session"] = getSession
         if _newclass: session = _swig_property(getSession)
    %}

    %pythoncode %{
         def fetch(self, timeout=None) :
             if timeout is None :
                 return self._fetch()
             else :
                 # Python API uses timeouts in seconds,
                 # but C++ API uses milliseconds
                 return self._fetch(Duration(int(1000*timeout)))
    %}
}

%extend qpid::messaging::Sender {
    %pythoncode %{
         def send(self, object, sync=True) :
             if isinstance(object, Message):
                 message = object
             else:
                 message = Message(object)
             return self._send(message, sync)
         
         __swig_getmethods__["capacity"] = getCapacity
         __swig_setmethods__["capacity"] = setCapacity
         if _newclass: capacity = _swig_property(getCapacity, setCapacity)

         __swig_getmethods__["session"] = getSession
         if _newclass: session = _swig_property(getSession)
    %}
}


%extend qpid::messaging::Message {
    %pythoncode %{
         # UNSPECIFIED was module level before, but I do not
         # know how to insert python code at the top of the module.
         # (A bare "%pythoncode" inserts at the end.
         UNSPECIFIED=object()
         def __init__(self, content=None, content_type=UNSPECIFIED, id=None,
                      subject=None, user_id=None, reply_to=None,
                      correlation_id=None, durable=None, priority=None,
                      ttl=None, properties=None):
             this = _cqpid.new_Message('')
             try: self.this.append(this)
             except: self.this = this
             if content :
                 self.content = content
             if content_type != UNSPECIFIED :
                 self.content_type = content_type
             if id is not None :
                 self.id = id
             if subject is not None :
                 self.subject = subject
             if user_id is not None :
                 self.user_id = user_id
             if reply_to is not None :
                 self.reply_to = reply_to
             if correlation_id is not None :
                 self.correlation_id = correlation_id
             if durable is not None :
                 self.durable = durable
             if priority is not None :
                 self.priority = priority
             if ttl is not None :
                 self.ttl = ttl
             if properties is not None :
                 # Can't set properties via (inst).getProperties, because
                 # the typemaps make a copy of the underlying properties.
                 # Instead, set via setProperty for the time-being
                 for k, v in properties.iteritems() :
                     self.setProperty(k, v)

         def _get_content(self) :
             if self.content_type == "amqp/list" :
                 return decodeList(self)
             if self.content_type == "amqp/map" :
                 return decodeMap(self)
             return self.getContent()
         def _set_content(self, content) :
             if isinstance(content, basestring) :
                 self.setContent(content)
             elif isinstance(content, list) or isinstance(content, dict) :
                 encode(content, self)
             else :
                 # Not a type we can handle.  Try setting it anyway,
                 # although this will probably lead to a swig error
                 self.setContent(content)
         __swig_getmethods__["content"] = _get_content
         __swig_setmethods__["content"] = _set_content
         if _newclass: content = _swig_property(_get_content, _set_content)

         __swig_getmethods__["content_type"] = getContentType
         __swig_setmethods__["content_type"] = setContentType
         if _newclass: content_type = _swig_property(getContentType,
                                                     setContentType)

         __swig_getmethods__["id"] = getMessageId
         __swig_setmethods__["id"] = setMessageId
         if _newclass: id = _swig_property(getMessageId, setMessageId)

         __swig_getmethods__["subject"] = getSubject
         __swig_setmethods__["subject"] = setSubject
         if _newclass: subject = _swig_property(getSubject, setSubject)

         __swig_getmethods__["priority"] = getPriority
         __swig_setmethods__["priority"] = setPriority
         if _newclass: priority = _swig_property(getPriority, setPriority)

         def getTtl(self) :
             return self._getTtl().getMilliseconds()/1000.0
         def setTtl(self, duration) :
             self._setTtl(Duration(int(1000*duration)))
         __swig_getmethods__["ttl"] = getTtl
         __swig_setmethods__["ttl"] = setTtl
         if _newclass: ttl = _swig_property(getTtl, setTtl)

         __swig_getmethods__["user_id"] = getUserId
         __swig_setmethods__["user_id"] = setUserId
         if _newclass: user_id = _swig_property(getUserId, setUserId)

         __swig_getmethods__["correlation_id"] = getCorrelationId
         __swig_setmethods__["correlation_id"] = setCorrelationId
         if _newclass: correlation_id = _swig_property(getCorrelationId,
                                                       setCorrelationId)

         __swig_getmethods__["redelivered"] = getRedelivered
         __swig_setmethods__["redelivered"] = setRedelivered
         if _newclass: redelivered = _swig_property(getRedelivered,
                                                    setRedelivered)

         __swig_getmethods__["durable"] = getDurable
         __swig_setmethods__["durable"] = setDurable
         if _newclass: durable = _swig_property(getDurable, setDurable)

         __swig_getmethods__["properties"] = getProperties
         if _newclass: properties = _swig_property(getProperties)

         def getReplyTo(self) :
             return self._getReplyTo().str()
         def setReplyTo(self, address_str) :
             self._setReplyTo(Address(address_str))
         __swig_getmethods__["reply_to"] = getReplyTo
         __swig_setmethods__["reply_to"] = setReplyTo
         if _newclass: reply_to = _swig_property(getReplyTo, setReplyTo)
         
         def __repr__(self):
             args = []
             for name in ["id", "subject", "user_id", "reply_to",
                          "correlation_id", "priority", "ttl",
                          "durable", "redelivered", "properties",
                          "content_type"] :
                 value = getattr(self, name)
                 if value : args.append("%s=%r" % (name, value))
             if self.content is not None:
                 if args:
                     args.append("content=%r" % self.content)
                 else:
                     args.append(repr(self.content))
             return "Message(%s)" % ", ".join(args)
    %}
}

%pythoncode %{
# Bring into module scope
UNSPECIFIED = Message.UNSPECIFIED
%}
