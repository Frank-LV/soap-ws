package com.centeractive;

import com.centeractive.soap.*;
import com.centeractive.soap.WsdlUtils.SoapHeader;
import com.centeractive.soap.domain.OperationWrapper;
import com.centeractive.soap.protocol.SoapVersion;
import com.ibm.wsdl.xml.WSDLReaderImpl;
import org.apache.log4j.Logger;
import org.apache.xmlbeans.SchemaGlobalElement;
import org.apache.xmlbeans.SchemaType;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import javax.wsdl.*;
import javax.wsdl.extensions.ExtensibilityElement;
import javax.wsdl.extensions.soap.SOAPBinding;
import javax.wsdl.extensions.soap.SOAPOperation;
import javax.wsdl.extensions.soap12.SOAP12Binding;
import javax.wsdl.xml.WSDLReader;
import javax.xml.namespace.QName;
import java.io.StringWriter;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class SoapBuilder {

    private final static Logger log = Logger.getLogger(SoapBuilder.class);

    private Definition definition;
    private SchemaDefinitionWrapper definitionWrapper;
    private Map<QName, String[]> multiValues = null; // TODO What are multiValues???
    private SoapContext context;

    public void setContext(SoapContext context) {
        this.context = context;
    }

    public SoapBuilder(URL wsdlUrl) throws WSDLException {
        this(SoapContext.builder().create(), wsdlUrl);
    }

    public SoapBuilder(SoapContext context, URL wsdlUrl) throws WSDLException {
        WSDLReader reader = new WSDLReaderImpl();
        this.definition = reader.readWSDL(wsdlUrl.toString());
        this.definitionWrapper = new SchemaDefinitionWrapper(definition, wsdlUrl.toString());
        this.context = context;
    }

    public void setMultiValues(Map<QName, String[]> multiValues) {
        this.multiValues = multiValues;
    }

    // ----------------------------------------------------------
    // EMPTY MESSAGE GENERATORS
    // ----------------------------------------------------------
    public String buildEmptyMessage(QName bindingQName) {
        return buildEmptyMessage(getSoapVersion(getBindingByName(bindingQName)));
    }

    public static String buildEmptyMessage(SoapVersion soapVersion) {
        SampleXmlUtil generator = new SampleXmlUtil(false);
        generator.setTypeComment(false);
        generator.setIgnoreOptional(true);
        return generator.createSample(soapVersion.getEnvelopeType());
    }

    // ----------------------------------------------------------
    // FAULT MESSAGE GENERATORS
    // ----------------------------------------------------------
    public static String buildFault(String faultcode, String faultstring, SoapVersion soapVersion) {
        SampleXmlUtil generator = new SampleXmlUtil(false);
        generator.setTypeComment(false);
        generator.setIgnoreOptional(true);
        String emptyResponse = buildEmptyFault(generator, soapVersion);
        if (soapVersion == SoapVersion.Soap11) {
            emptyResponse = XmlUtils.setXPathContent(emptyResponse, "//faultcode", faultcode);
            emptyResponse = XmlUtils.setXPathContent(emptyResponse, "//faultstring", faultstring);
        } else if (soapVersion == SoapVersion.Soap12) {
            emptyResponse = XmlUtils.setXPathContent(emptyResponse, "//soap:Value", faultcode);
            emptyResponse = XmlUtils.setXPathContent(emptyResponse, "//soap:Text", faultstring);
            emptyResponse = XmlUtils.setXPathContent(emptyResponse, "//soap:Text/@xml:lang", "en");
        }
        return emptyResponse;
    }

    public String buildFault(String faultcode, String faultstring, QName bindingQName) {
        return buildFault(faultcode, faultstring, getSoapVersion(getBindingByName(bindingQName)));
    }

    public String buildEmptyFault(QName bindingQName) {
        return buildEmptyFault(getSoapVersion(getBindingByName(bindingQName)));
    }

    public static String buildEmptyFault(SoapVersion soapVersion) {
        SampleXmlUtil generator = new SampleXmlUtil(false);
        String emptyResponse = buildEmptyFault(generator, soapVersion);
        return emptyResponse;
    }


    // ----------------------------------------------------------
    // INPUT MESSAGE GENERATORS
    // ----------------------------------------------------------
    public String buildSoapMessageFromInput(OperationWrapper operation) throws Exception {
        return buildSoapMessageFromInput(operation, context);
    }

    public String buildSoapMessageFromInput(OperationWrapper operation, SoapContext context) throws Exception {
        Binding binding = getBindingByName(operation.getBindingName());
        BindingOperation bindingOperation = getBindingOperation(binding, operation);

        SoapVersion soapVersion = getSoapVersion(binding);
        boolean inputSoapEncoded = WsdlUtils.isInputSoapEncoded(bindingOperation);
        SampleXmlUtil xmlGenerator = new SampleXmlUtil(inputSoapEncoded, context);
        xmlGenerator.setMultiValues(multiValues);
        xmlGenerator.setIgnoreOptional(!context.isBuildOptional());

        XmlObject object = XmlObject.Factory.newInstance();
        XmlCursor cursor = object.newCursor();
        cursor.toNextToken();
        cursor.beginElement(soapVersion.getEnvelopeQName());

        if (inputSoapEncoded) {
            cursor.insertNamespace("xsi", Constants.XSI_NS);
            cursor.insertNamespace("xsd", Constants.XSD_NS);
        }

        cursor.toFirstChild();
        cursor.beginElement(soapVersion.getBodyQName());
        cursor.toFirstChild();

        if (WsdlUtils.isRpc(definition, bindingOperation)) {
            buildRpcRequest(bindingOperation, soapVersion, cursor, xmlGenerator);
        } else {
            buildDocumentRequest(bindingOperation, cursor, xmlGenerator);
        }

        if (context.isAlwaysBuildHeaders()) {
            BindingInput bindingInput = bindingOperation.getBindingInput();
            if (bindingInput != null) {
                List<?> extensibilityElements = bindingInput.getExtensibilityElements();
                List<SoapHeader> soapHeaders = WsdlUtils.getSoapHeaders(extensibilityElements);
                addHeaders(soapHeaders, soapVersion, cursor, xmlGenerator);
            }
        }
        cursor.dispose();

        try {
            StringWriter writer = new StringWriter();
            XmlUtils.serializePretty(object, writer);
            return writer.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return object.xmlText();
        }
    }

    // ----------------------------------------------------------
    // OUTPUT MESSAGE GENERATORS
    // ----------------------------------------------------------
    public String buildSoapMessageFromOutput(OperationWrapper operation)
            throws Exception {
        return buildSoapMessageFromOutput(operation, context);
    }

    public String buildSoapMessageFromOutput(OperationWrapper operation, SoapContext context) throws Exception {
        Binding binding = getBindingByName(operation.getBindingName());
        BindingOperation bindingOperation = getBindingOperation(binding, operation);

        boolean inputSoapEncoded = WsdlUtils.isInputSoapEncoded(bindingOperation);
        SampleXmlUtil xmlGenerator = new SampleXmlUtil(inputSoapEncoded, context);
        xmlGenerator.setIgnoreOptional(!context.isBuildOptional());
        xmlGenerator.setMultiValues(multiValues);
        SoapVersion soapVersion = getSoapVersion(binding);


        XmlObject object = XmlObject.Factory.newInstance();
        XmlCursor cursor = object.newCursor();
        cursor.toNextToken();
        cursor.beginElement(soapVersion.getEnvelopeQName());

        if (inputSoapEncoded) {
            cursor.insertNamespace("xsi", Constants.XSI_NS);
            cursor.insertNamespace("xsd", Constants.XSD_NS);
        }

        cursor.toFirstChild();

        cursor.beginElement(soapVersion.getBodyQName());
        cursor.toFirstChild();

        if (WsdlUtils.isRpc(definition, bindingOperation)) {
            buildRpcResponse(bindingOperation, soapVersion, cursor, xmlGenerator);
        } else {
            buildDocumentResponse(bindingOperation, cursor, xmlGenerator);
        }

        if (context.isAlwaysBuildHeaders()) {
            // bindingOutput will be null for one way operations,
            // but then we shouldn't be here in the first place???
            BindingOutput bindingOutput = bindingOperation.getBindingOutput();
            if (bindingOutput != null) {
                List<?> extensibilityElements = bindingOutput.getExtensibilityElements();
                List<SoapHeader> soapHeaders = WsdlUtils.getSoapHeaders(extensibilityElements);
                addHeaders(soapHeaders, soapVersion, cursor, xmlGenerator);
            }
        }
        cursor.dispose();

        try {
            StringWriter writer = new StringWriter();
            XmlUtils.serializePretty(object, writer);
            return writer.toString();
        } catch (Exception e) {
            log.warn("Exception during message generation" ,e);
            return object.xmlText();
        }
    }

    // ----------------------------------------------------------
    // UTILS
    // ----------------------------------------------------------
    public Definition getDefinition() {
        return definition;
    }

    public SchemaDefinitionWrapper getSchemaDefinitionWrapper() {
        return definitionWrapper;
    }

    public BindingOperation getBindingOperation(Binding binding, OperationWrapper op) {
        BindingOperation operation = binding.getBindingOperation(op.getOperationName(),
                op.getOperationInputName(), op.getOperationOutputName());
        if (operation == null) {
            throw new SoapBuilderException("Operation not found");
        }
        return operation;
    }

    public static String getSOAPActionUri(BindingOperation operation) {
        List extensions = operation.getExtensibilityElements();
        if (extensions != null) {
            for (int i = 0; i < extensions.size(); i++) {
                ExtensibilityElement extElement = (ExtensibilityElement) extensions.get(i);
                if (extElement instanceof SOAPOperation) {
                    SOAPOperation soapOp = (SOAPOperation) extElement;
                    return soapOp.getSoapActionURI();
                }
            }
        }
        return null;
    }

    public static OperationWrapper getOperation(Binding binding, BindingOperation operation) {
        String soapAction = getSOAPActionUri(operation);
        if(operation.getOperation().getStyle().equals(OperationType.REQUEST_RESPONSE)) {
            return new OperationWrapper(binding.getQName(), operation.getName(), operation.getBindingInput().getName(),
                    operation.getBindingOutput().getName(), soapAction);
        } else {
            return new OperationWrapper(binding.getQName(), operation.getName(), operation.getBindingInput().getName(),
                    null, soapAction);
        }

    }

    public static OperationWrapper getOperation(Binding binding, BindingOperation operation, String soapAction) {
        if(operation.getOperation().getStyle().equals(OperationType.REQUEST_RESPONSE)) {
            return new OperationWrapper(binding.getQName(), operation.getName(), operation.getBindingInput().getName(),
                    operation.getBindingOutput().getName(), soapAction);
        } else {
            return new OperationWrapper(binding.getQName(), operation.getName(), operation.getBindingInput().getName(),
                    null, soapAction);
        }

    }


    // --------------------------------------------------------------------------
    // Internal methods - END OF PUBLIC API
    // --------------------------------------------------------------------------
    private Binding getBindingByName(QName bindingName) {
        Binding binding = this.definition.getBinding(bindingName);
        if (binding == null) {
            throw new SoapBuilderException("Binding not found");
        }
        return binding;
    }

    private BindingOperation getOperationByName(QName bindingName, String operationName, String operationInputName, String operationOutputName) {
        Binding binding = getBindingByName(bindingName);
        if (binding == null) {
            return null;
        }
        BindingOperation operation = binding.getBindingOperation(operationName, operationInputName, operationOutputName);
        if (operation == null) {
            throw new SoapBuilderException("Operation not found");
        }
        return operation;
    }


    private static SoapVersion getSoapVersion(Binding binding) {
        List<?> list = binding.getExtensibilityElements();

        SOAPBinding soapBinding = WsdlUtils.getExtensiblityElement(list, SOAPBinding.class);
        if (soapBinding != null) {
            if ((soapBinding.getTransportURI().startsWith(Constants.SOAP_HTTP_TRANSPORT) || soapBinding
                    .getTransportURI().startsWith(Constants.SOAP_MICROSOFT_TCP))) {
                return SoapVersion.Soap11;
            }
        }

        SOAP12Binding soap12Binding = WsdlUtils.getExtensiblityElement(list, SOAP12Binding.class);
        if (soap12Binding != null) {
            if (soap12Binding.getTransportURI().startsWith(Constants.SOAP_HTTP_TRANSPORT)
                    || soap12Binding.getTransportURI().startsWith(Constants.SOAP12_HTTP_BINDING_NS)
                    || soap12Binding.getTransportURI().startsWith(Constants.SOAP_MICROSOFT_TCP)) {
                return SoapVersion.Soap12;
            }
        }
        throw new SoapBuilderException("SOAP binding not recognized");
    }


    private void addHeaders(List<SoapHeader> headers, SoapVersion soapVersion, XmlCursor cursor, SampleXmlUtil xmlGenerator) throws Exception {
        // reposition
        cursor.toStartDoc();
        cursor.toChild(soapVersion.getEnvelopeQName());
        cursor.toFirstChild();

        cursor.beginElement(soapVersion.getHeaderQName());
        cursor.toFirstChild();

        for (int i = 0; i < headers.size(); i++) {
            SoapHeader header = headers.get(i);

            Message message = definition.getMessage(header.getMessage());
            if (message == null) {
                log.error("Missing message for header: " + header.getMessage());
                continue;
            }

            Part part = message.getPart(header.getPart());

            if (part != null)
                createElementForPart(part, cursor, xmlGenerator);
            else
                log.error("Missing part for header; " + header.getPart());
        }
    }

    private void buildDocumentResponse(BindingOperation bindingOperation, XmlCursor cursor, SampleXmlUtil xmlGenerator)
            throws Exception {
        Part[] parts = WsdlUtils.getOutputParts(bindingOperation);

        for (int i = 0; i < parts.length; i++) {
            Part part = parts[i];

            if (!WsdlUtils.isAttachmentOutputPart(part, bindingOperation)
                    && (part.getElementName() != null || part.getTypeName() != null)) {
                XmlCursor c = cursor.newCursor();
                c.toLastChild();
                createElementForPart(part, c, xmlGenerator);
                c.dispose();
            }
        }
    }

    private void buildDocumentRequest(BindingOperation bindingOperation, XmlCursor cursor, SampleXmlUtil xmlGenerator)
            throws Exception {
        Part[] parts = WsdlUtils.getInputParts(bindingOperation);

        for (int i = 0; i < parts.length; i++) {
            Part part = parts[i];
            if (!WsdlUtils.isAttachmentInputPart(part, bindingOperation)
                    && (part.getElementName() != null || part.getTypeName() != null)) {
                XmlCursor c = cursor.newCursor();
                c.toLastChild();
                createElementForPart(part, c, xmlGenerator);
                c.dispose();
            }
        }
    }

    private void createElementForPart(Part part, XmlCursor cursor, SampleXmlUtil xmlGenerator) throws Exception {
        QName elementName = part.getElementName();
        QName typeName = part.getTypeName();

        if (elementName != null) {
            cursor.beginElement(elementName);

            if (definitionWrapper.hasSchemaTypes()) {
                SchemaGlobalElement elm = definitionWrapper.getSchemaTypeLoader().findElement(elementName);
                if (elm != null) {
                    cursor.toFirstChild();
                    xmlGenerator.createSampleForType(elm.getType(), cursor);
                } else
                    log.error("Could not find element [" + elementName + "] specified in part [" + part.getName() + "]");
            }

            cursor.toParent();
        } else {
            // cursor.beginElement( new QName(
            // wsdlContext.getWsdlDefinition().getTargetNamespace(), part.getName()
            // ));
            cursor.beginElement(part.getName());
            if (typeName != null && definitionWrapper.hasSchemaTypes()) {
                SchemaType type = definitionWrapper.getSchemaTypeLoader().findType(typeName);

                if (type != null) {
                    cursor.toFirstChild();
                    xmlGenerator.createSampleForType(type, cursor);
                } else
                    log.error("Could not find type [" + typeName + "] specified in part [" + part.getName() + "]");
            }

            cursor.toParent();
        }
    }

    private void buildRpcRequest(BindingOperation bindingOperation, SoapVersion soapVersion, XmlCursor cursor, SampleXmlUtil xmlGenerator)
            throws Exception {
        // rpc requests use the operation name as root element
        String ns = WsdlUtils.getSoapBodyNamespace(bindingOperation.getBindingInput().getExtensibilityElements());
        if (ns == null) {
            ns = WsdlUtils.getTargetNamespace(definition);
            log.warn("missing namespace on soapbind:body for RPC request, using targetNamespace instead (BP violation)");
        }

        cursor.beginElement(new QName(ns, bindingOperation.getName()));
        // TODO
        if (xmlGenerator.isSoapEnc())
            cursor.insertAttributeWithValue(new QName(soapVersion.getEnvelopeNamespace(),
                    "encodingStyle"), soapVersion.getEncodingNamespace());

        Part[] inputParts = WsdlUtils.getInputParts(bindingOperation);
        for (int i = 0; i < inputParts.length; i++) {
            Part part = inputParts[i];

            if (WsdlUtils.isAttachmentInputPart(part, bindingOperation)) {
                // TODO - generation of attachment flag could be externalized
                // if (iface.getSettings().getBoolean(WsdlSettings.ATTACHMENT_PARTS)) {
                XmlCursor c = cursor.newCursor();
                c.toLastChild();
                c.beginElement(part.getName());
                c.insertAttributeWithValue("href", part.getName() + "Attachment");
                c.dispose();
                // }
            } else {
                if (definitionWrapper.hasSchemaTypes()) {
                    QName typeName = part.getTypeName();
                    if (typeName != null) {
                        // TODO - Don't know whether will work
                        // SchemaType type = wsdlContext.getInterfaceDefinition().findType(typeName);
                        SchemaType type = definitionWrapper.findType(typeName);

                        if (type != null) {
                            XmlCursor c = cursor.newCursor();
                            c.toLastChild();
                            c.insertElement(part.getName());
                            c.toPrevToken();

                            xmlGenerator.createSampleForType(type, c);
                            c.dispose();
                        } else
                            log.warn("Failed to find type [" + typeName + "]");
                    } else {
                        SchemaGlobalElement element = definitionWrapper.getSchemaTypeLoader().findElement(part.getElementName());
                        if (element != null) {
                            XmlCursor c = cursor.newCursor();
                            c.toLastChild();
                            c.insertElement(element.getName());
                            c.toPrevToken();

                            xmlGenerator.createSampleForType(element.getType(), c);
                            c.dispose();
                        } else
                            log.warn("Failed to find element [" + part.getElementName() + "]");
                    }
                }
            }
        }
    }

    private void buildRpcResponse(BindingOperation bindingOperation, SoapVersion soapVersion, XmlCursor cursor, SampleXmlUtil xmlGenerator)
            throws Exception {
        // rpc requests use the operation name as root element
        BindingOutput bindingOutput = bindingOperation.getBindingOutput();
        String ns = bindingOutput == null ? null : WsdlUtils.getSoapBodyNamespace(bindingOutput
                .getExtensibilityElements());

        if (ns == null) {
            ns = WsdlUtils.getTargetNamespace(definition);
            log.warn("missing namespace on soapbind:body for RPC response, using targetNamespace instead (BP violation)");
        }

        cursor.beginElement(new QName(ns, bindingOperation.getName() + "Response"));
        if (xmlGenerator.isSoapEnc())
            cursor.insertAttributeWithValue(new QName(soapVersion.getEnvelopeNamespace(),
                    "encodingStyle"), soapVersion.getEncodingNamespace());

        Part[] inputParts = WsdlUtils.getOutputParts(bindingOperation);
        for (int i = 0; i < inputParts.length; i++) {
            Part part = inputParts[i];
            if (WsdlUtils.isAttachmentOutputPart(part, bindingOperation)) {
                // if( iface.getSettings().getBoolean( WsdlSettings.ATTACHMENT_PARTS ) )
                {
                    XmlCursor c = cursor.newCursor();
                    c.toLastChild();
                    c.beginElement(part.getName());
                    c.insertAttributeWithValue("href", part.getName() + "Attachment");
                    c.dispose();
                }
            } else {
                if (definitionWrapper.hasSchemaTypes()) {
                    QName typeName = part.getTypeName();
                    if (typeName != null) {
                        SchemaType type = definitionWrapper.findType(typeName);

                        if (type != null) {
                            XmlCursor c = cursor.newCursor();
                            c.toLastChild();
                            c.insertElement(part.getName());
                            c.toPrevToken();

                            xmlGenerator.createSampleForType(type, c);
                            c.dispose();
                        } else
                            log.warn("Failed to find type [" + typeName + "]");
                    } else {
                        SchemaGlobalElement element = definitionWrapper.getSchemaTypeLoader().findElement(part.getElementName());
                        if (element != null) {
                            XmlCursor c = cursor.newCursor();
                            c.toLastChild();
                            c.insertElement(element.getName());
                            c.toPrevToken();

                            xmlGenerator.createSampleForType(element.getType(), c);
                            c.dispose();
                        } else
                            log.warn("Failed to find element [" + part.getElementName() + "]");
                    }
                }
            }
        }
    }


    private static String buildEmptyFault(SampleXmlUtil generator, SoapVersion soapVersion) {
        String emptyResponse = buildEmptyMessage(soapVersion);
        try {
            // XmlObject xmlObject = XmlObject.Factory.parse( emptyResponse );
            XmlObject xmlObject = XmlUtils.createXmlObject(emptyResponse);
            XmlCursor cursor = xmlObject.newCursor();

            if (cursor.toChild(soapVersion.getEnvelopeQName()) && cursor.toChild(soapVersion.getBodyQName())) {
                SchemaType faultType = soapVersion.getFaultType();
                Node bodyNode = cursor.getDomNode();
                Document dom = XmlUtils.parseXml(generator.createSample(faultType));
                bodyNode.appendChild(bodyNode.getOwnerDocument().importNode(dom.getDocumentElement(), true));
            }

            cursor.dispose();
            emptyResponse = xmlObject.toString();
        } catch (Exception e) {
            throw new SoapBuilderException(e);
        }
        return emptyResponse;
    }


}
