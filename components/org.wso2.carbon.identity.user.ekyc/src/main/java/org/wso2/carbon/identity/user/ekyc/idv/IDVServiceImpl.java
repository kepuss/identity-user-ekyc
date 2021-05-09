/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.user.ekyc.idv;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.wso2.carbon.identity.configuration.mgt.core.ConfigurationManager;
import org.wso2.carbon.identity.configuration.mgt.core.exception.ConfigurationManagementException;
import org.wso2.carbon.identity.configuration.mgt.core.model.Resource;
import org.wso2.carbon.identity.user.ekyc.dto.*;
import org.wso2.carbon.identity.user.ekyc.exception.IDVException;
import org.wso2.carbon.identity.user.ekyc.util.IDVConstants;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.wso2.carbon.identity.user.ekyc.util.EKYCConfigurationMapper.buildEKYCConfigurationFromResource;
import static org.wso2.carbon.identity.user.ekyc.util.IDVConstants.EKYC_RESOURCE_TYPE;
import static org.wso2.carbon.identity.user.ekyc.util.IDVConstants.RESOURCE_NAME;

public class IDVServiceImpl implements IDVService {

    private HttpClient httpClient;
    private ConfigurationManager configurationManager;

    private static final Log log = LogFactory.getLog(IDVServiceImpl.class);

    public IDVServiceImpl(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
        try {
            httpClient = newClient();
        } catch (Exception e) {
            log.error("IDV Http client creation error", e);
        }
    }

    @Override
    public EKYCSessionDTO generateNewSession(String service, List<String> claims) throws IDVException, ConfigurationManagementException {
        try {
            EKYCSesssionRequestDTO ekycSesssionRequest = new EKYCSesssionRequestDTO(service, claims, getEKYCConfiguration()
                    .getCallbackUrl());
            HttpPost request = getJsonPostRequest(IDVConstants.UrlPaths.GET_SESSION_PATH, ekycSesssionRequest);
            EKYCSessionDTO ekycSessionDTO = executeCall(request, EKYCSessionDTO.class);
            return ekycSessionDTO;
        } catch (IOException e) {
            throw new IDVException(IDVConstants.ErrorMessages.IDV_CONNECTION_ERROR, e);
        }
    }

    @Override
    public JsonObject getSessionVc(String userId, String sessionId) throws IDVException, ConfigurationManagementException {
        try {
            EKYCVCRequestDTO ekycvcRequestDTO = new EKYCVCRequestDTO(userId, sessionId);
            HttpPost request = getJsonPostRequest(IDVConstants.UrlPaths.POST_VC_PATH, ekycvcRequestDTO);
            HttpResponse response = httpClient.execute(request);
            if (response.getStatusLine().getStatusCode() != 200) {
                throw new IDVException(IDVConstants.ErrorMessages.IDV_CONNECTION_RESPONSE_CODE_NOT_OK);
            }
            String responseBody = EntityUtils.toString(response.getEntity());
            JsonObject vc = new JsonParser().parse(responseBody).getAsJsonObject();
            return vc;
        } catch (IOException e) {
            throw new IDVException(IDVConstants.ErrorMessages.IDV_CONNECTION_ERROR, e);
        }
    }

    @Override
    public EKYCVerifyClaimResponseDTO getVerifyClaim(String sessionId, String claim, String value) throws IDVException, ConfigurationManagementException {
        try {
            EKYCVerifyClaimRequestDTO ekycVerifyClaimRequestDTO = new EKYCVerifyClaimRequestDTO(sessionId, claim, value);
            HttpPost request = getJsonPostRequest(IDVConstants.UrlPaths.POST_CLAIM_VERIFY_PATH, ekycVerifyClaimRequestDTO);
            EKYCVerifyClaimResponseDTO ekycVerifyClaimResponseDTO = executeCall(request, EKYCVerifyClaimResponseDTO.class);
            return ekycVerifyClaimResponseDTO;
        } catch (IOException e) {
            throw new IDVException(IDVConstants.ErrorMessages.IDV_CONNECTION_ERROR, e);
        }
    }

    private <T> T executeCall(HttpPost request, Class<T> clazz) throws IOException, IDVException {
        HttpResponse response = httpClient.execute(request);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IDVException(IDVConstants.ErrorMessages.IDV_CONNECTION_RESPONSE_CODE_NOT_OK);
        }
        String responseBody = EntityUtils.toString(response.getEntity());
        T result = new Gson().fromJson(responseBody, clazz);
        return result;
    }

    private HttpPost getJsonPostRequest(String path, Object body) throws UnsupportedEncodingException, ConfigurationManagementException {
        HttpPost request = new HttpPost(getEKYCConfiguration().getUrl() + path);
        request.setHeader("Content-type", ContentType.APPLICATION_JSON.toString());
        request.setEntity(new StringEntity(new Gson().toJson(body)));
        return request;
    }

    private EKYCConfigurationDTO getEKYCConfiguration() throws ConfigurationManagementException {
        Resource resource = configurationManager
                .getResource(EKYC_RESOURCE_TYPE, RESOURCE_NAME);
        return buildEKYCConfigurationFromResource(resource);
    }


    private HttpClient newClient() throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException, ConfigurationManagementException {
        if (getEKYCConfiguration().isSkipTlsCheck()) {
            CloseableHttpClient httpClient = HttpClients.custom().
                    setHostnameVerifier(new AllowAllHostnameVerifier()).
                    setSslcontext(new SSLContextBuilder().loadTrustMaterial(null, new TrustStrategy() {
                        public boolean isTrusted(X509Certificate[] arg0, String arg1) throws CertificateException {
                            return true;
                        }
                    }).build()).build();
            return httpClient;
        } else {
            return HttpClients.createDefault();
        }
    }
}




