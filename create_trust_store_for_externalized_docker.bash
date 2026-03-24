#!/bin/bash
# Synopsis
# This script creates a bunch of certificates and a trust-store for localdev testing an externalized docker-compose.
# The certificates are used by integration tests
#
# Usage: use -v as verbose if you want to print more info about the created certificates

rootCACertFile=$(pwd)/src/test/resources/oauth2/certs/rootCA/rootCA.crt
rootCACertPath=$(pwd)/src/test/resources/oauth2/certs/rootCA
allSanConfFile=$(pwd)/localdev/docker-externalized/all_san.conf

ROOT_CA_NODE_TYPE=$(stat -c '%F' ${rootCACertFile} 2>/dev/null)
if [ "${ROOT_CA_NODE_TYPE}" == "regular file" ]; then
   echo "Working directory rootCACertFile ok."
else
   echo "ERROR: File rootCACertFile (${rootCACertFile}) is missing: detected node-type: \"${ROOT_CA_NODE_TYPE}\". Make sure you have the working directory set to binder git root directory."
   exit -1
fi

rm -rf ./localdev/docker-externalized
mkdir -p ./localdev/docker-externalized
cd ./localdev/docker-externalized
# create san
export dockerHostname=$(echo "${DOCKER_HOST}" | sed 's|tcp://\(.*\):.*|\1|')

echo "
req_extensions = req_ext
distinguished_name = req_distinguished_name
prompt = no

[ req_distinguished_name ]
C  = CA
ST = Ontario
L  = Kanata
O  = Solace Systems
OU = Solace Systems
CN = ${dockerHostname}

[ req_ext ]
subjectAltName = @alt_names

[ alt_names ]
DNS.1 = ${dockerHostname}
DNS.2 = localhost
DNS.3 = solaceOAuth
DNS.4 = solaceoauth
DNS.5 = solaceoauth2
DNS.6 = *.solace_internal_net
DNS.7 = *.solace_msg_net
DNS.8 = *.solace_msg_network
IP.1 = 127.0.0.1
IP.2 = 0:0:0:0:0:0:0:1
# ... (add more SAN entries)
" > ${allSanConfFile}
# generate solace broker cert
mkdir broker
openssl genrsa -out ./broker/solbroker.key 2048
openssl req -new -key ./broker/solbroker.key -out ./broker/solbroker.csr -config ${allSanConfFile}
openssl x509 -req -in ./broker/solbroker.csr -CA ${rootCACertFile} -CAkey ${rootCACertPath}/rootCA.key -CAcreateserial -out ./broker/solbroker.crt -days 7300 -sha256 -extensions req_ext -extfile ${allSanConfFile}
cat ./broker/solbroker.key ./broker/solbroker.crt > ./broker/solbroker.pem
if [ "$1" == "-v" ]; then
  openssl x509 -in ./broker/solbroker.crt -text -noout
fi
# generate client cert
mkdir client
openssl genrsa -out ./client/client.key 2048
openssl req -new -key ./client/client.key -out ./client/client.csr -config ${allSanConfFile}
openssl x509 -req -in ./client/client.csr -CA ${rootCACertFile} -CAkey ${rootCACertPath}/rootCA.key -CAcreateserial -out ./client/client.crt -days 7300 -sha256
cat ./client/client.key ./client/client.crt > ./client/client.pem
if [ "$1" == "-v" ]; then
  openssl x509 -in ./client/client.crt -text -noout
fi
# prepare truststore
keytool -import -trustcacerts -alias root_ca -file ${rootCACertPath}/rootCA.der -keystore ./client/client-truststore.p12 -storepass changeMe123 -noprompt
keytool -v -list -keystore ./client/client-truststore.p12 -storepass changeMe123
openssl pkcs12 -export -in ./client/client.pem -inkey ./client/client.key -name client -out ./client/client.p12 -passout pass:changeMe123
keytool -importkeystore -srckeystore ./client/client.p12 -srcstoretype PKCS12 -destkeystore ./client/client-keystore.jks -deststoretype JKS -srcstorepass changeMe123 -deststorepass changeMe123
# create keystore cert
mkdir keycloak
openssl genrsa -out ./keycloak/keycloak.key 2048
openssl req -new -key ./keycloak/keycloak.key -out ./keycloak/keycloak.csr -config ${allSanConfFile}
openssl x509 -req -in ./keycloak/keycloak.csr -CA ${rootCACertFile} -CAkey ${rootCACertPath}/rootCA.key -CAcreateserial -out ./keycloak/keycloak.crt -days 7300 -sha256 -extensions req_ext -extfile ${allSanConfFile}
cat ./keycloak/keycloak.key ./keycloak/keycloak.crt > ./keycloak/keycloak.pem
if [ "$1" == "-v" ]; then
  openssl x509 -in ./keycloak/keycloak.crt -text -noout
fi

echo "Generating certificates and truststore completed successfully."