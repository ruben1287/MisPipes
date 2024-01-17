library identifier: 'poc-mail-notif@develop', retriever: modernSCM(
  [$class: 'GitSCMSource',
   remote: 'REPODELIBS/sharedLibrary.git',
   credentialsId: 'jenkins-ssh-key'])

def causeUserID = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')     // PARA USR EJECUTOR

pipeline {
    agent { 
        label "AGENTE"
    }

    environment {
		EMAIL="EMAIL" 				    // CASILLA DESDE DONDE SE ENVIAN LOS MAILS.
		SENDTOEMAIL="DESTINATARIOS"
        FUNCIONALIDAD="FUNCIONALIDAD"
        SERVICE_DIRECTORY="FE-Biometrics"
		APP="NOMBRE_APP"
        CREDENTIAL_GIT="CREDENTIAL_GIT-ssh-key"  
        PATH_DEPLOY = "${env.GIT_BRANCH == "develop" ? "dev" : "test"}" //el nombre de la carpeta de donde toma el env var. Si no es development la rama, es release. Hay que agregarlo al mb pipeline
		PROJECT_NAME = 'NOMBRE PROYECTO'
        
        // Nexus -------- server
		CREDENCIAL_NEXUS="CREDENCIAL_NEXUS"      				// BUSCAR CORRESPONDIENTES AL AMBIENTE.
		URL_NEXUS="http://IP_NEXUS:8080"						// TENER EN CUENTA FUTURO NEXUS PRODUCTIVO.
		
        // Nexus -------- target		
		ARTFACID_NEXUS="ARTFACID_NEXUS"
        GROUPID_NEXUS= "GROUPID_NEXUS"							// PATH DEL GRUPO EN NEXUS - SIEMPRE UTILIZAR 
        URL_NEXUS_DOCKER="URL_NEXUS_DOCKER:8083"                // TENER EN CUENTA FUTURO NEXUS PRODUCTIVO
		IMAGE_DOCKER="IMAGE_DOCKER"
        image = "image-fe"  

		// Nexus -------- temp	
		JSON_RETURN=""											//Se inica Vacia para utilizar en notificacion
		JSON_NAME=""											//se inicia
        
        //-- ENV y MENU
        JAR_NAME="NOMBREJAR.tar.gz"
        SOURCE_DIR="target"                                     // RETROCOMPATIBILIDAD PARA DEPLOY.
        PUERTO_MIN='8889'                                       // PARA BÚSQUEDA DE PUERTO LIBRE
        AWS_REGION = "us-east-1"                                // REGION DEL AWS
        ELK_URL="ELK_URL"
        REPOSITORY_NEXUS="repo"
        URL_NEXUS_TRIVY="URL_NEXUS_TRIVY"
        URL_NEXUS_BE_DOCKER="URL_NEXUS_BE_DOCKER:8084" 

    options {
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '10'))
    }

    stages {
        stage('Config env') {
            steps {
                echo "---------------------------------------------"
				echo " - - INICIO: Configuracion del ambiente  - - "
				echo "---------------------------------------------"
                        
                script {
                    load "jenkins/${PATH_DEPLOY}/varEnv.env"
                    env.DEFAULT_SUBJECT="${ENV_NAME} - COMPILACION: ${FUNCIONALIDAD}"
                }//C_script
                script {JSON_RETURN=sh (script: 'jq -r ".version" package.json',returnStdout: true).trim()}
				script {JSON_NAME=sh (script: 'jq -r ".name" package.json',returnStdout: true).trim()}

                echo "-----------------------------------------------"
                echo " - - FIN: Configuracion del ambiente - - "
			    echo "-----------------------------------------------"
            }//C_steps
        }//C_Config

		stage("Compile") {
			agent{
				docker { 
					image 'URL_NEXUS_DOCKER:8083/docker-agents/node14:1.0.1' 
					registryUrl 'http://URL_NEXUS_DOCKER:8083'
                    args '--add-host=nexus:IP'
					reuseNode true
				}
			}//C_Agent

            steps {
                script{
					echo "Compiling..."
					sh "whoami"
					sh "rm -f package-lock.json"
                        
					sh "npm install --legacy-peer-deps --verbose"
					sh "CI=false npm run build:prod" 
					sh "chmod -R 755 build"
                }
			}	
		}//C_compile

        stage("Pack") { 
        	steps {
				echo "Packing..."
				sh '''
					tar -cvzf ${APP}.tar.gz ./build
				'''
                sh "ls"
                sh "pwd"
                sh "mkdir -p ${SOURCE_DIR} && mv ${JAR_NAME} ${SOURCE_DIR}/${JAR_NAME}"
				sh "chmod 775 -R ${SOURCE_DIR}"		
			}
		}//C_pack

        stage("Retain") { /// para la rama release
		    when{
                branch 'release-*'
                }
        	
            steps {
				echo "Retaining artifact in Nexus..."
                sh "ls"
				sh "ls -la build"
                sh "pwd"
                //sh "/opt/maven/bin/mvn deploy:deploy-file -Durl=${URL_NEXUS}${REPOSITORY_NEXUS} -DrepositoryId=nexus -Dfile=${APP}.tar.gz -DgroupId=${GROUPID_NEXUS} -DartifactId=${ARTFACID_NEXUS} -Dversion=${JSON_RETURN} -Dpackaging=tar.gz -DgeneratePom=true "
				withCredentials([usernamePassword(credentialsId: 'Release-NEXUS-TOOLTEST', passwordVariable: 'PASSWORD_NEXUS', usernameVariable: 'USERNAME_NEXUS')]) {
                    sh "curl -v -u ${USERNAME_NEXUS}:${PASSWORD_NEXUS} -X POST '${URL_NEXUS}/service/rest/v1/components?repository=${REPOSITORY_NEXUS}' -H 'accept: application/json' -H 'Content-Type: multipart/form-data' -F 'maven2.groupId=${GROUPID_NEXUS}' -F 'maven2.artifactId=${ARTFACID_NEXUS}' -F 'maven2.version=${JSON_RETURN}' -F 'maven2.generate-pom=true' -F 'maven2.packaging=tar.gz' -F 'maven2.asset1.extension=tar.gz' -F 'maven2.asset1=@./target/${APP}.tar.gz' "
                }
            }
		}

        stage("Pull Base Image") {
            steps {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    echo "Pulling base image..."
                    sh '''
                    docker login -u ${USER_NAME} -p ${PASSWORD} ${URL_NEXUS_DOCKER}
                    docker pull ${URL_NEXUS_DOCKER}/nginx:alpine
                    docker tag ${URL_NEXUS_DOCKER}/nginx:alpine nginx:alpine
                    '''
                }
            }
		}//C_puulBaseImage
        
        stage("Create Docker Image") {
            steps {
                sh "pwd"
                sh "ls -la"
				sh "cd ${SOURCE_DIR} && ls -al || true"
                sh "tar -xvf '${SOURCE_DIR}/${JAR_NAME}' -C . || true"
                //sh "tar -xvf '${JAR_NAME}' -C . || true"
				sh '''
					docker build -t '''+IMAGE_DOCKER+''':'''+JSON_RETURN+'''  . 
                '''
            }
        }//c_stagecreateimage

        stage("Check Free Port") { 
            steps {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    script {
                        sh '''
                            wget http://URL_NEXUS_DOCKER:8081/repository/Devops/scripts/Soporte/findAFreePort/latest/findAFreePort.sh --proxy=off --user $USER_NAME --password $PASSWORD
                            chmod +x findAFreePort.sh
                        '''     
                        port = sh (returnStdout: true, script: "./findAFreePort.sh -p ${PUERTO_MIN}").trim()
                    }
                }
            }
        }//c_checkPort

        stage("RunDocker") { 
            steps {
                sh '''
                    docker run -it -d -p '''+port+''':8889  --rm --name $image '''+IMAGE_DOCKER+''':'''+JSON_RETURN+''' 
                '''             
            }           
        }//c_runDocker
        
        stage("DockerStop") { 
            steps {
                sh '''
                    docker stop $image
                '''             
            }           
        }//c_dockerStop

        stage("RegisterDocker") { 
			steps {
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    sh '''
                        docker login -u ${USER_NAME} -p ${PASSWORD} ${URL_NEXUS_DOCKER}
                        docker tag ${IMAGE_DOCKER}:'''+JSON_RETURN+''' ${URL_NEXUS_DOCKER}/${IMAGE_DOCKER}:'''+JSON_RETURN+''' 
                        docker push ${URL_NEXUS_DOCKER}/${IMAGE_DOCKER}:'''+JSON_RETURN+''' 
                    '''     
				}
            }     
		}//C_RegisterDocker

        stage("ScanTrivy") {      
            environment {
                // -- Variables para trivy sec scan
                HTTP_PROXY=""
                HTTPS_PROXY=""
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
                                         img_ver: "${JSON_RETURN}" ] )                   
                }  

                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: "elk-stack" , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    script {
                        trivy.sendReport( [ elk_url: "${ELK_URL}" ] )
                    }
                }// FIN WITHCREDENTIALS
            } 
        } //C_ScanTrivy

        stage('Push Image to ECR') { //CAMBIAR LA CREDENCIAL PARA EL AMBIENTE DESTINO
            when{
                branch 'develop'
            }

            environment {
                SERVICE_CREDS= credentials"${SERVICE_CREDS}" 
            }

            steps{
                script{
                    sh '''
                    export HOME=${WORKSPACE}
                    export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                    export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW
                    aws ecr get-login-password --region ${AWS_REGION} --endpoint-url $ECR_BIO | docker login --username AWS --password-stdin ${ECR_REGISTRY}

                    docker tag $IMAGE_DOCKER:'''+JSON_RETURN+''' $ECR_REGISTRY/$ECR_REPOSITORY/$image:'''+JSON_RETURN+'''
                    docker tag $IMAGE_DOCKER:'''+JSON_RETURN+''' $ECR_REGISTRY/$ECR_REPOSITORY/$image:latest

                    docker push $ECR_REGISTRY/$ECR_REPOSITORY/$image:'''+JSON_RETURN+'''
                    docker push $ECR_REGISTRY/$ECR_REPOSITORY/$image:latest
                    
                    unset AWS_ACCESS_KEY_ID
                    unset AWS_SECRET_ACCESS_KEY
                    '''
                }
            }
        }//c_Push Image to ECR

        stage('deploy') { //CAMBIAR LA CREDENCIAL PARA PREPRO Y PROD SEGUN CONVENGA.
            when{
                branch 'develop'
            }

            environment {
                SERVICE_CREDS= credentials"${SERVICE_CREDS}" 
            }

            steps {
                sh '''
                export HOME=${WORKSPACE}
                export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW

                aws sts get-caller-identity   
                aws eks --region ${AWS_REGION} update-kubeconfig --name $CLUSTER_NAME

                ls -la jenkins/$PATH_DEPLOY/
                
				kubectl get nodes -n biometria-ns
				kubectl get pods -n biometria-ns
                cat jenkins/$PATH_DEPLOY/deployment.yml
				kubectl apply -f jenkins/$PATH_DEPLOY/deployment.yml -n biometria-ns
				kubectl rollout restart deployment ${image} -n biometria-ns
				kubectl get pods -n biometria-ns
                               
                unset AWS_ACCESS_KEY_ID
                unset AWS_SECRET_ACCESS_KEY
                '''
            }
        }//C_deploy
    }//C_stages

    post {
        always{
            script {
                // Tomamos la variable que guarda la version hay que automatizar la deteccion de esta variable
                // Puede ser POM_VERSION en mvn o JSON_RETURN en npm
                env.PKG_VERSION="${JSON_RETURN}" 
            }
            sendEmailwithAttachment    currentBuild.result
            echo "Limpio dir y finalizo"
        }//c_always
        
        cleanup {
            dockerImageCleanup ( [ image_docker: "${IMAGE_DOCKER}",
                                    version_tag: "${JSON_RETURN}"

            ] )
        }
	}//c_post
}//C_pipe