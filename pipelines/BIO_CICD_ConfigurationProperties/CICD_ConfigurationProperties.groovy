#!groovy
library identifier: 'poc-mail-notif@master', retriever: modernSCM(
  [$class: 'GitSCMSource',
   remote: 'REPO/sharedLibrary.git', 
   credentialsId: 'jenkins-ssh-key'])

def causeUserID = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')     // PARA USR EJECUTOR

pipeline {
    agent {
        label 'AGENTE'
    }
    
    environment {
        //-- CLONE
        SERVICE_DIRECTORY="MS-Bio-ConfigurationProperties"                      // PATH DONDE TRABAJARA EL JENKINS
        CREDENTIAL_GIT="CREDENTIAL_GIT-ssh-key"                                 // CREDENCIAL PARA LA COMUNICACION CON EL GIT-MACRO

        //--COVERAGE
        SONAR_NAME="BIO - MS ConfigurationProperties"                           // NOMBRE DEL PROJECTO DE SONAR

        //-- NOTIFICACION
        PROJECT_NAME="Properties - Biometria"
        EMAIL="EMAIL"                                                           // CASILLA DE ENVIO DE MAIL
        SENDTOEMAIL="DESTINATARIO"

		//-- NEXUS
		CREDENCIAL_NEXUS="CREDENCIAL_NEXUS"                                     // BUSCAR CORRESPONDIENTES AL AMBIENTE.
        URL_NEXUS="http://URL_NEXUS:8081"                                       // TENER EN CUENTA FUTURO NEXUS PRODUCTIVO.
        REPOSITORY_NEXUS="REPOSITORY_NEXUS"                                     // ESTO PUEDE ESTAR SUJETO A CAMBIOS EN EL NEXUS PRODUCTIVO.        REPOSITORY_ID="nexus"
        GROUPID_NEXUS= "ar.com.macro.biometria.configuration-properties"        // PATH DEL GRUPO EN NEXUS - SIEMPRE UTILIZAR "/" .
        ARTIFACTID_NEXUS= "configuration-properties"            				// NO AGREGAR LA '/' AL FINAL

        //-- DEPLOY				
		PRIVATEKEYDEPLOY="PRIVATEKEYDEPLOY"			                            // ID JENKINS DE CREDENCIAL A UTILIZAR PARA CONECTARSE CON EL AMBIENTE DESTINO. TEST-FE:eploy-user-password-test-ssh TEST-BE:PrivatKeyBE  PREPRO:jenkins - PROD:jenkins_prod
		HOST_NAME = "NombreSRV"							                        // EQUIPO A DEPLOYAR.
        TAR_CONF1 = 'test1'
        //TAR_CONF2 = 'pre-prd2'                                                // PARA AMBIENTES PREPRODUCTIVO O PRODUCTIVOS
		
        //-- SERVICE_NAME= "startgateway.sh" 						            // SH QUE INICIA EL MICROSERVICIO.
		HOST_WORKSPACE_PATH = "/opt/configuration-properties" 		            // RUTA DONDE SE DEPLOYA EL CORE.
		SOURCE_DIR="target" 									                // RETROCOMPATIBILIDAD PARA DEPLOY.
        JAR_NAME = 'JAR_NAME.tar.gz' 				                            // JAR QUE SE UTILIZA PARA EL MICROSERVICIO.
        PATH_DEPLOY = "${env.GIT_BRANCH == "development" ? "dev" : "test"}" //el nombre de la carpeta de donde toma el env var. Si no es development la rama, es release. Hay que agregarlo al mb pipeline
        VERSION=" "
        AWS_REGION = "us-east-1"

        //-- PROXY
        HTTPS_PROXY="http://XXXX:XXXX"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }

    stages {
        stage('Config env') {
            steps {
                echo "---------------------------------------------"
				echo " - - INICIO: Configuracion del ambiente ${PATH_DEPLOY} - - "
				echo "---------------------------------------------"
                //se obtiene el nombre del branch quitando "release-"
                script {  
                    if (env.BRANCH_NAME == 'development'){
                        echo "ambiente dev"}
                        else{
                        branchName = "$GIT_BRANCH"
                        VERSION = branchName.split('-')[1]
                    }
                }
                script {
                    load "jenkins/${PATH_DEPLOY}/varEnv.env"
                    DEFAULT_SUBJECT="${ENV_NAME} - NOTIFICACION DE ACTIVIDAD - DEPLOY: ${SERVICE_DIRECTORY}"
                    env.NO_PROXY="${URL_NEXUS},${ECR_BIO},${AWS_ECR_VPCE},${AWS_EKS}"
                }//C_script
                
                echo "BRANCH = ${env.GIT_BRANCH}"
                echo "BRANCH CORTADO = ${VERSION}"
                echo "PATH_DEPLOY= ${PATH_DEPLOY}"
                echo "ENV_NAME= ${ENV_NAME}"

                echo "-----------------------------------------------"
                echo " - - FIN: Configuracion del ambiente - - "
			    echo "-----------------------------------------------"
            }//C_steps
        }//C_Config

        stage('Build Properties') {  // SE ARMA EL CONFIGMAP.YML
            
            steps {	
                echo "----------------------"
				echo "INICIO: BUILD PROPERTIES"
				echo "----------------------"
                
                script {
					def full_string = "test dev"
					sh '''
						awk '{print $2}' ./cloud/$PATH_DEPLOY/kustomization.yaml | sed -n 's/application.properties//p' >> ./directorios.txt
                    '''
					sh "cat ./directorios.txt"
					def list = readFile(file: 'directorios.txt').readLines()
					def lista_ambiente = full_string.split(" ")
					sh "mkdir -p properties"
					sh "cd properties"		
						for( i in lista_ambiente ) {
							echo "ESTO ES LA LISTA DE AMBIENTES - ${i}"
							for (j in list) {
								print "${j}"
								echo "ESTO ES EL DIRECTORIO ${j}"
								echo "ESTO ES LISTA DE AMBIENTES:${i}"							
								sh "mkdir -p ./properties/${i}"
								sh "cd ./properties/${i}"
								sh "cat ./cloud/${i}/${j}*.properties > ./cloud/${i}/${j}application.properties"
							}
						}
						for (e in lista_ambiente) {
							sh "pwd && ls -lhS"
							sh "/home/jenkins/kustom build cloud/${e}/. > ./properties/${e}/configmap.yml"
							sh "mv ./jenkins/${e}/* ./properties/${e}/"
						}
					}//c_script   
                echo "----------------------"
				echo "FIN: BUILD PROPERTIES"
				echo "----------------------"
                }
            }//c_BuildProperties
        
        stage("Compile") {
            when{
                branch 'release-*'
            }

            steps {
                echo "----------------------"
				echo "INICIO: Compiling..."
				echo "----------------------"
					sh "chmod -R 0774 *"
					sh "cd properties"
                    sh "tar -czvf ${JAR_NAME}.tar.gz ./properties/*"
                    sh "chmod -R 0774 *"	
				echo "----------------------"
				echo "FIN: Compiling"
				echo "----------------------"
            }
        }//c_Compile

        stage('Retain') {
            when{
                branch 'release-*'
            }
            steps {
                echo "----------------------"
				echo "INICIO: Retain in nexus..."
				echo "----------------------"
                withCredentials([usernamePassword(credentialsId: '$CREDENCIAL_NEXUS', passwordVariable: 'PASSWORD_NEXUS', usernameVariable: 'USERNAME_NEXUS')]) {
					sh " curl -v -u ${USERNAME_NEXUS}:${PASSWORD_NEXUS} -X POST '${URL_NEXUS}/service/rest/v1/components?repository=${REPOSITORY_NEXUS}' -H 'accept: application/json' -H 'Content-Type: multipart/form-data' -F 'maven2.groupId=${GROUPID_NEXUS}' -F 'maven2.artifactId=${ARTIFACTID_NEXUS}' -F 'maven2.version=${VERSION}' -F 'maven2.generate-pom=true' -F 'maven2.packaging=tar.gz' -F 'maven2.asset1.extension=tar.gz' -F 'maven2.asset1=@${ARTIFACTID_NEXUS}.tar.gz' "
				}//c_withCredentials
                echo "----------------------"
				echo "FIN: Retain in nexus..."
				echo "----------------------"
            }
        }//c_retain

        stage ('Deploy properties') {
            when{
                branch 'development' //development
            }

            environment {
                SERVICE_CREDS= credentials"${SERVICE_CREDS}"
            }

             steps {
				echo "----------------------"
				echo "INICIO: DEPLOY PROPERTIES..."
				echo "----------------------"
                echo "Apply  aws configmap "
                    script {
					    sh '''
                        export HOME=${WORKSPACE}
                        export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                        export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW
					
                        aws eks --region ${AWS_REGION} update-kubeconfig --name ${CLUSTER_NAME}
                        
                        kubectl apply -f ./properties/${PATH_DEPLOY}/ -n biometria-ns
                        #kubectl apply -f ./jenkins/${PATH_DEPLOY}/db-service.yml -n biometria-ns
                        #kubectl apply -f ./jenkins/${PATH_DEPLOY}/be-pod-secrets.yml -n biometria-ns
					
					    kubectl get pods -n biometria-ns
					
                        unset AWS_ACCESS_KEY_ID
                        unset AWS_SECRET_ACCESS_KEY
                    '''
					}// FIN SCRIPT		
				echo "----------------------"
				echo "FIN: DEPLOY PROPERTIES..."
				echo "----------------------"
			}//FIN STEP
        }//FIN STAGE
        
        stage('Reinicio de servicios') {
            when{
                branch 'development' 
            }

            environment {
                SERVICE_CREDS= credentials"${SERVICE_CREDS}"
            }

            steps {
                echo "----------    Se reinician los pods"
					//DEBE EJECUTARSE TODO EN LA MISMA SESION sh
                script{
                    def status = sh(script: '''
					    export HOME=${WORKSPACE}
                        export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                        export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW
                        aws eks --region ${AWS_REGION} update-kubeconfig --name ${CLUSTER_NAME}
                    
                        kubectl get deployments -n NAMESPACE
					
                        kubectl rollout restart deployment INICIATIVA -n NAMESPACE
                        
                        kubectl rollout status deployment INICIATIVA -n NAMESPACE --timeout=60s

					    kubectl get pods -n NAMESPACE

					    unset AWS_ACCESS_KEY_ID
                        unset AWS_SECRET_ACCESS_KEY
                    '''
                    , returnStatus: true)
                    if (status != 0) {
                        unstable("unstable build because script failed")
                    }
                }
            }//c_steps
        }//c_stageReinicio
    }//C_stages

    post {
        always {
            script {
                env.BUILD_RESULT = currentBuild.currentResult
            }
            echo "Finalizo"
            //archiveArtifacts artifacts: '*.tar.gz', fingerprint: true
            // NO UTILIZAR VARIABLES - DEFINIR EL PATH DONDE SE ENCUENTRE EL JAR
        }//c_always
        

        success {
            //VER QUE VA EN LUGAR DE INPUT_PARAMS y VER_NEXUS
            echo "${env.BUILD_RESULT}"
            emailext (
            mimeType: 'text/html',
            from: "${EMAIL}",
            body: """
            <h2 style="text-align:center; border: 2px solid black; border-radius: 25px; background:#33cc33; color: white;">Despliegue Satisfactorio</h2>
                    <p> Se completo de forma <b>SATISFACTORIA</b> el despliegue de <b>${SERVICE_DIRECTORY}</b> en el ambiente de  ${ENV_NAME} - Version: ${VERSION}</p>  
                    </br>
                    <p>Puede referise a los siguientes enlaces para mayor detalle:</p>
                    <ul>
                        <li><b>Job ejecutado en Jenkins y detalles de ejecucion: </b> <a href=${env.BUILD_URL}> Jenkins </a> </li>
                        <li><b>Artefacto disponible en Nexus para la descarga</a></li>
                    <ul>
            """,
            attachLog: true,
            to: "${SENDTOEMAIL}",
            replyTo: "${SENDTOEMAIL}",
            subject: "${DEFAULT_SUBJECT} - VERSION: ${VERSION}"
            )
        }//c_succes
                    
        failure {
            echo "${env.BUILD_RESULT}"
            emailext (
                    mimeType: 'text/html',
                    from: "${EMAIL}",
                    body: """
                    <h2 style="text-align:center; border: 2px solid black; border-radius: 25px; background:#FDFD96; color: black;">Despliegue Fallido</h2>
                    <p> Se completo de forma <b>FALLIDA</b> el despliegue de <b>${SERVICE_DIRECTORY}</b> en el ambiente de  ${ENV_NAME}</p>  
                        </br>
                        <p>Puede referise a los siguientes enlaces para mayor detalle:</p>  
                    <ul>
                        <li><b>Job ejecutado en Jenkins y detalles de ejecucion: </b> <a href=${env.BUILD_URL}> Jenkins </a> </li>							
                        <li><b>Log de Error: </b> <a href=${env.BUILD_URL}/consoleText> Log </a> </li>							
                    <ul>
                    """,
                    attachLog: true,
                    to: "${SENDTOEMAIL}",
                    replyTo: "${SENDTOEMAIL}",
                    subject: "${DEFAULT_SUBJECT}"
            )
        }//c_failure

        unstable {
            echo "${env.BUILD_RESULT}"
            emailext (
                mimeType: 'text/html',
                from: "${EMAIL}",
                body: """
                    <h2 style="text-align:center; border: 2px solid black; border-radius: 25px; background:#FDFD96; color: black;">Despliegue Inestable</h2>
                    <p> El despliegue se realizo con un resultado <b>INESTABLE</b> debido a Timeout en uno o mas servicios, ver log de ejecucion. Ambiente: ${ENV_NAME}</p>  
                    </br>
                    <p>Puede referise a los siguientes enlaces para mayor detalle:</p>    
                    <ul>
                        <li><b>Job ejecutado en Jenkins y detalles de ejecucion: </b> <a href=${env.BUILD_URL}> Jenkins </a> </li>							
                        <li><b>Log de la ejecucion fallida: </b> <a href=${env.BUILD_URL}/consoleText> Log </a> </li>
                        <li><b>Ejecutado por: </b> ${causeUserID.userName}</li>
                    <ul>
                    """,
                attachLog: true,
                to: "${SENDTOEMAIL}",
                replyTo: "${SENDTOEMAIL}",
                subject: "ABORTED - ${DEFAULT_SUBJECT}"
            )
        }//c_aborted

        aborted {
            echo "${env.BUILD_RESULT}"
            emailext (
                mimeType: 'text/html',
                from: "${EMAIL}",
                body: """
                    <h2 style="text-align:center; border: 2px solid black; border-radius: 25px; background:#FDFD96; color: black;">Despliegue Abortado</h2>
                    <p> El despliegue fue <b>ABORTADO</b> debido a que el pipeline fallo por Timeout - Servicio: ${SERVICE_DIRECTORY} | Ambiente: ${ENV_NAME}</p>  
                    </br>
                    <p>Puede referise a los siguientes enlaces para mayor detalle:</p>    
                    <ul>
                        <li><b>Job ejecutado en Jenkins y detalles de ejecucion: </b> <a href=${env.BUILD_URL}> Jenkins </a> </li>							
                        <li><b>Log de la ejecucion fallida: </b> <a href=${env.BUILD_URL}/consoleText> Log </a> </li>
                        <li><b>Ejecutado por: </b> ${causeUserID.userName}</li>
                    <ul>
                    """,
                to: "${SENDTOEMAIL}",
                replyTo: "${SENDTOEMAIL}",
                subject: "ABORTED - ${DEFAULT_SUBJECT}"
            )
        }//c_aborted

        cleanup {
            echo 'Drop Files on local Repo'
            deleteDir()  // SE BORRA EL WORKSPACE UTILIZADO POR JENKINS
        }       
    }//c_post	
}//c_Pipeline