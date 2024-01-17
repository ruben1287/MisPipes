library identifier: 'poc-mail-notif@develop', retriever: modernSCM(
  [$class: 'GitSCMSource',
   remote: 'REPO/sharedLibrary.git', 
   credentialsId: 'jenkins-ssh-key'])

def causeUserID = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')            // PARA USR EJECUTOR

pipeline {

    agent {
        label 'AGENTE'
    }

    parameters {
        choice choices: ['SI', 'NO'], description: 'Ejecutar ValidVersion', name: 'OPCION'
    }

    environment {
    //NOTIFICATIONS
        EMAIL="EMAIL@dominio.com.ar"                                // CASILLA DESDE DONDE SE ENVIAN LOS MAILS.
        //SENDTOEMAIL="rubenramirez@accusys.com.ar"
        SENDTOEMAIL="Destinatario"    // DESTINATARIOS DE MAIL, CAMBIAR PARA PASAR A DEVELOPMENT

    //NEXUS
        ARTFACID_NEXUS= "enro-canal"
        CREDENCIAL_NEXUS="CREDENCIAL_NEXUS"
        URL_NEXUS="http://IP:8081"
        SERVICE_DIRECTORY="EnroCanal"                              // PATH DONDE TRABAJARA EL JENKINS
        REPOSITORY_NEXUS="releases"                                // CAMBIAR PARA PASAR A DEVELOPMENT

    //DOCKER
        IMAGE_DOCKER="enro-canal"
        ELK_URL="ELK_URL"                                           
        PUERTO_MIN='8889'                                           // PARA BÚSQUEDA DE PUERTO LIBRE
        URL_NEXUS_DOCKER="URL_NEXUS:8083"                           // TENER EN CUENTA FUTURO NEXUS PRODUCTIVO
        URL_NEXUS_TRIVY="URL_NEXUS"

    //ENV
        AWS_REGION = "us-east-1"                                    // REGION DEL AWS
        http_proxy="http://IP:PUERTO"
        https_proxy="${http_proxy}"
        HTTPS_PROXY="${http_proxy}"
        HTTP_PROXY="${http_proxy}"
        POMPATH="pom.xml"
        PATH_DEPLOY = "${env.GIT_BRANCH == "development" ? "dev" : "test"}" //el nombre de la carpeta de donde toma el env var. Si no es development la rama, es release. Hay que agregarlo al mb pipeline CAMBIAR PARA PASAR A DEVELOPMENT
        JAR_NAME="XXX-enro-canal.jar"
        
    //SONAR    
        SONAR_HOST="http://SRVSONAR:9000/"
        SONAR_TOKEN="SONAR_TOKEN"
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }

    stages {
        stage('Config env') {
            steps {
                echo "${env.GIT_BRANCH}"
                echo "${PATH_DEPLOY}"
                echo "---------------------------------------------"
				echo " - - INICIO: Configuracion del ambiente DEV - - "
				echo "---------------------------------------------"
                        
                script {
                    load "jenkins/${PATH_DEPLOY}/varEnv.env"
                    env.POM_VERSION = readMavenPom(file: "${POMPATH}").getVersion()
                    env.GROUPID = readMavenPom(file: "${POMPATH}").getGroupId()
                    env.NO_PROXY="${URL_NEXUS},${ECR_BIO},${AWS_ECR_VPCE},${AWS_EKS}"
                    DEFAULT_SUBJECT_W="${ENV_NAME} - COMPILACION: ${SERVICE_DIRECTORY}"    
                }//C_script
                    
                echo "-----------------------------------------------"
                echo " - - FIN: Configuracion del ambiente - - "
			    echo "-----------------------------------------------"
            }//C_steps
        }//C_Config
        
        stage('ValidVersion') {
            when{
                branch 'release-*'
            }

            steps {
                script{
                if (params.OPCION == 'NO'){
                        echo "No se ejecuta Valid Version"
                    }else if (params.OPCION == 'SI') {
                        validVersion([ pom_version: "${POM_VERSION}",
                                url_nexus: "${URL_NEXUS}",
                                artfacid_nexus: "${ARTFACID_NEXUS}",
                                credencial_nexus: "${CREDENCIAL_NEXUS}" ])
                    } else {
                        echo "Opcion invalida"
                    }
                }//C_script
            }//C_steps
        }//C_ValidVersion

        stage('Configure pom.xml') {
            when{
                branch 'release-*'
            }

            steps {
                sh '''           
                    sed -i 's/IP:PORT/IP:PORT/g' "pom.xml"
                    sed -i 's=<url>${nexus.url}/repository/maven-releases/</url>=<url>${nexus.url}/repository/${REPOSITORY_NEXUS}/</url>=g' "pom.xml"
                    sed -i 's/>nexus-releases</>nexus</g' "pom.xml"
                '''
            }
        }//C_configPom
        
        stage('Maven') {
            agent {
                docker { 
                    image 'URL_NEXUS:8083/docker-agents/maven3-jdk11:latest' 
                    registryUrl 'http://URL_NEXUS:8083'
                    args '--add-host=nexus4:URL_NEXUS'
                    reuseNode true
                }
            }

            stages {
                stage('Construct') {
                    steps {
						echo "Build ${SERVICE_DIRECTORY} Service Jar"
							script {
                                mvnSteps.mvnConstruct( [ serviceName: "${SERVICE_DIRECTORY}",
														 compileParams: "-Pkubernetes",
														 testParams: "-Dmaven.test.skip=true",
														 buildNumber: "${env.BUILD_NUMBER}" ] )
							}
							echo "Build ${SERVICE_DIRECTORY} Service Jar --- END"
					}
                }//C_construct

                stage('Test') {
                    steps {
						echo "Test ${SERVICE_DIRECTORY} Service Jar"
                        script {
								mvnSteps.mvnTest( [ serviceName: "${SERVICE_DIRECTORY}" ])
							}
                            
						echo "Tested ${SERVICE_DIRECTORY} Service Jar --- END"
					}
                }//C_test

                stage('Retain') {
                    when{
                        branch 'release-*'
                    }

                    steps {         
                        echo "Deploy Nexus ${SERVICE_DIRECTORY}"
							script{
								mvnSteps.mvnDeploy( [ serviceName: "${SERVICE_DIRECTORY}",
													 compileParams: "-Pkubernetes",
														 testParams: "-Dmaven.test.skip=true",
													 buildNumber: "${env.BUILD_NUMBER}" ] )
							}
							echo "Deploy Nexus ${SERVICE_DIRECTORY} --- END"
                     }
                }//C_Retain
            }//C_stages
        }//C_maven
            
        stage('Coverage') {
            agent {
                docker { 
                    image 'sonarsource/sonar-scanner-cli' 
                    args '-e SONAR_HOST_URL="${SONAR_HOST}" -e SONAR_LOGIN="${SONAR_TOKEN}" -e POM_VERSION="${POM_VERSION}"'
                    reuseNode true
                }   
            }//c_agent

            steps {
                sh 'sonar-scanner -Dsonar.host.url=${SONAR_HOST_URL} -Dsonar.projectVersion=${POM_VERSION}'// -Dsonar.branch.name=${env.GIT_BRANCH}' //Se agrego la parte del sonar branch name
            }
        }//c_coverage
        
        stage("Pull base image") {
            steps {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    echo "Pulling base image..."
                    sh '''
                        docker login -u ${USER_NAME} -p ${PASSWORD} ${URL_NEXUS_DOCKER}
                        docker pull ${URL_NEXUS_DOCKER}/adoptopenjdk/openjdk11:alpine-slim
                        docker tag ${URL_NEXUS_DOCKER}/adoptopenjdk/openjdk11:alpine-slim adoptopenjdk/openjdk11:alpine-slim
                    '''
                }
            }
        }//C_PullBaseImage

        stage("Create Image") {
            steps {
                    sh '''
                        docker build --build-arg JAR_NAME='''+JAR_NAME+''' -t '''+IMAGE_DOCKER+''':'''+POM_VERSION+''' . 
                    '''
            }
        }//C_CreateImage

        stage("Check Free Port") { 
            steps {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    script {
                        sh '''
                            wget http://URL_NEXUS:8081/repository/Devops/scripts/Soporte/findAFreePort/latest/findAFreePort.sh --proxy=off --user $USER_NAME --password $PASSWORD
                            chmod +x findAFreePort.sh
                        '''     
                        port = sh (returnStdout: true, script: "./findAFreePort.sh -p ${PUERTO_MIN}").trim()
                        env.FREE_PORT = port 
                    }    
                }    
            }
        }//C_CheckPort

        stage("Docker Run") { 
            steps {
                sh '''
                    docker run -it -d -p ${FREE_PORT}:8889  --rm --name biometrics-${IMAGE_DOCKER} '''+IMAGE_DOCKER+''':'''+POM_VERSION+''' 
                '''             
            }           
        }//C_DockerRun
        
        stage("Docker Stop") { 
            steps {
                sh '''
                    docker stop biometrics-${IMAGE_DOCKER}
                '''         
            }           
        }//C_DockerStop

        stage("Register Docker") { 
            steps {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    sh '''
                        docker login -u ${USER_NAME} -p ${PASSWORD} ${URL_NEXUS_DOCKER}
                        docker tag '''+IMAGE_DOCKER+''':'''+POM_VERSION+''' ${URL_NEXUS_DOCKER}/${IMAGE_DOCKER}:'''+POM_VERSION+''' 
                        docker push ${URL_NEXUS_DOCKER}/${IMAGE_DOCKER}:'''+POM_VERSION+''' 
                    '''             
                }
            }    
        }//C_RegisterDocker

        stage("ScanTrivy") { 
            environment {
                // -- Variables para trivy sec scan
                HTTP_PROXY="http://IP:PORT"
                HTTPS_PROXY="http://IP:PORT"
                NO_PROXY="${ELK_URL},${URL_NEXUS_TRIVY}"
                TRIVY_SEVERITY="CRITICAL,HIGH,MEDIUM" // "UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL"
                TRIVY_IGNORE_UNFIXED="false" // ignorar las vuln sin fix
                TRIVY_NO_PROGRESS="true"
                TRIVY_EXIT_CODE="0" // Salida del shell 0 sigue el pipe, 1 falla si encuentra vuln
                TRIVY_FORMAT="json"
                TRIVY_OUTPUT="/usr/source/reporte-trivy.json" // --env TRIVY_OUTPUT="${TRIVY_OUTPUT}"
            }

            steps {
                script {
                    echo "Scan Trivy"
                    trivy.imageScanner( [ https_proxy: "${HTTPS_PROXY}",
                                        http_proxy: "${HTTP_PROXY}",
                                        no_proxy: "${NO_PROXY}",
                                        trivy_severity: "${TRIVY_SEVERITY}",
                                        trivy_ignore_unfixed: "${TRIVY_IGNORE_UNFIXED}",
                                        trivy_no_progress: "${TRIVY_NO_PROGRESS}",
                                        trivy_exit_code: "${TRIVY_EXIT_CODE}",
                                        trivy_format: "${TRIVY_FORMAT}",
                                        trivy_output: "${TRIVY_OUTPUT}",
                                        url_nexus_docker: "${URL_NEXUS_DOCKER}",
                                        image_docker: "${IMAGE_DOCKER}",
                                        img_ver: "${POM_VERSION}"  ] )
                    withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "Cred-elk-stack" , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                        script {
                            trivy.sendReport( [ elk_url: "${ELK_URL}" ] )
                        }
                    }// FIN WITHCREDENTIALS
                }
            }
        }//C_ScanTrivy

        //PUSH

        stage('Push Image to ECR') {
            when{
                branch 'development'
            }

            environment {
                SERVICE_CREDS= credentials"${SERVICE_CREDS}"
            }

            steps{
                echo "------------------------------------"
                echo " - - INICIO: Push ECR - -"
                echo "------------------------------------"
                script{ 
                    sh '''
                    export HOME=${WORKSPACE}
                    export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                    export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW
                    aws ecr get-login-password --region ${AWS_REGION} --endpoint-url $ECR_BIO | docker login --username AWS --password-stdin ${ECR_REGISTRY}
                    
                    docker tag $IMAGE_DOCKER:'''+POM_VERSION+''' $ECR_REGISTRY/$ECR_REPOSITORY/$IMAGE_DOCKER:'''+POM_VERSION+'''
                    docker tag $IMAGE_DOCKER:'''+POM_VERSION+''' $ECR_REGISTRY/$ECR_REPOSITORY/$IMAGE_DOCKER:latest

                    docker push $ECR_REGISTRY/$ECR_REPOSITORY/$IMAGE_DOCKER:'''+POM_VERSION+'''
                    docker push $ECR_REGISTRY/$ECR_REPOSITORY/$IMAGE_DOCKER:latest
                    
                    unset AWS_ACCESS_KEY_ID
                    unset AWS_SECRET_ACCESS_KEY
                    '''
                }
            echo "-------------------------------------"
            echo " - - FIN: Push ECR - - "
            echo "-------------------------------------"    
            }//C_steps
        }//C_stage_Push

		stage ('Deploy...'){
            when{
                branch 'development' 
            }
            
            environment{
                SERVICE_CREDS= credentials"${SERVICE_CREDS}"
            }

            steps{
                echo "-------------------------------------------------------"
                echo " - - INICIO: Deploy EKS...."
                echo "-------------------------------------------------------"
                        //pre requisito para el apply -- existir el .kube/config en el nodo ejecutor
                        sh '''
                        export HOME=${WORKSPACE}
                        export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                        export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW
                        
                        aws eks --region ${AWS_REGION} update-kubeconfig --name ${CLUSTER_NAME}
                        
                        kubectl delete deployment ${IMAGE_DOCKER} --ignore-not-found=true -n NAMESPACE
                        
                        kubectl apply -f ./jenkins/$PATH_DEPLOY/deployment.yml -n NAMESPACE
                        
                        #kubectl rollout restart deployment ${IMAGE_DOCKER} -n NAMESPACE
                        
                        kubectl get pods -n NAMESPACE
                        
                        unset AWS_ACCESS_KEY_ID
                        unset AWS_SECRET_ACCESS_KEY
                        '''
                echo "--------------------------------------------------------"
                echo " - - FIN: Deploy - - "
                echo "--------------------------------------------------------"
                
            }//c_step
        }//c_deploy
    }//C_Stages

    post {
        always{
            script {
                POM_VERSION = readMavenPom(file: "${POMPATH}").getVersion()
                // Tomamos la variable que guarda la version hay que automatizar la deteccion de esta variable
                // Puede ser POM_VERSION en mvn o JSON_RETURN en npm
                env.PKG_VERSION="${POM_VERSION}" 
            }
        }//c_always

        success{
            script{
                env.DEFAULT_SUBJECT="OK - ${DEFAULT_SUBJECT_W}"
            }
            echo "DEFAULT_SUBJECT: ${env.DEFAULT_SUBJECT}"
            sendEmailwithAttachment currentBuild.result
        }//C_success

        failure{
            script{
                env.DEFAULT_SUBJECT="ERR - ${DEFAULT_SUBJECT_W}"
            }
            echo "DEFAULT_SUBJECT: ${env.DEFAULT_SUBJECT}"
            sendEmailwithAttachment currentBuild.result
        }//C_failure
        
        cleanup {
            dockerImageCleanup ( [ image_docker: "${IMAGE_DOCKER}",
                                    version_tag: "${POM_VERSION}"

            ] )
        }
    }//c_Post
}//C_Pipeline