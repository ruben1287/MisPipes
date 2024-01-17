#!groovy
def causeUserID = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')     // PARA USR EJECUTOR
pipeline {
    agent {
         label 'agent' 
    }

    environment {
		//-- REPO
		SERVICE_DIRECTORY="BBDD"                            // PATH DONDE TRABAJARA EL JENKINS
		CREDENTIAL_GIT="CREDENTIAL_GIT-ssh-key"             // CREDENCIAL PARA LA COMUNICACION CON EL GIT-MACRO
        		
		//--   ENV		
        PATH_DEPLOY = "${env.GIT_BRANCH == "devops" ? "test" : "dev"}" //el nombre de la carpeta de donde toma el env var. Si no es development la rama, es release. Hay que agregarlo al mb pipeline

        //--NEXUS
        CREDENCIAL_NEXUS="Credencial Nexus" 				// BUSCAR CORRESPONDIENTES AL AMBIENTE.
		URL_NEXUS="http://IP nexus"							// TENER EN CUENTA FUTURO NEXUS PRODUCTIVO.
        ARTFACID_NEXUS="sql"                                // NOMBRE DEL ARTEFACTO 
        GROUPID_NEXUS= "GROUPID_NEXUS" 	                    // PATH DEL GRUPO EN NEXUS
        REPOSITORY_NEXUS="REPOSITORY_NEXUS"
        //REPOSITORY_NEXUS="releases"


		//-- NOTIFICACIONES
        PROJECT_NAME = 'BBDD'                                   
        SENDTOEMAIL="A QUIEN LLEGA"
        DEFAULT_SUBJECT="DEPLOY ${PATH_DEPLOY} - ${PROJECT_NAME}"     // SUBJECT DEL MAIL A ENVIAR
        EMAIL="QUIEN ENVIA"                                          // CASILLA DE ENVIO DE MAIL
	}//c_enviroment
    
    stages {
        stage ('Config env'){
            steps{
                script {
                    load "jenkins/${PATH_DEPLOY}/environment.env"
                }
                echo "PATH_DEPLOY: ${PATH_DEPLOY}"
            }
        }//Config_env

        stage('ValidVersion') {
            when{
                branch 'release-*'
            }

            steps {
                script {  
                    if (env.BRANCH_NAME == 'devops') {
                        echo "ambiente dev"}
                        else{
                        branchName = "$GIT_BRANCH"
                        VERSION = branchName.split('-')[1]
                    }
                }
                echo "----- ValidVersion..."
                withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: CREDENCIAL_NEXUS , usernameVariable: 'USER_NAME', passwordVariable: 'PASSWORD']]) {
                    script {
                        try {
                            EXISTE_NEXUS = sh (returnStdout: true, script: "curl -u ${USER_NAME}:${PASSWORD} -X GET '${URL_NEXUS}/service/rest/v1/search/assets?version=${VERSION}&maven.extension=tar&maven.artifactId=${env.ARTFACID_NEXUS}' |  grep -Po '\"downloadUrl\" : \"\\K.+(?=\",)'").trim()
                        } catch (Exception ex) {
                            println("***** Salida por excepcion con mensaje: ${ex}")
                            EXISTE_NEXUS=""
                        }
                    }
                }//C_withCredentials

                echo "***** Salida de EXISTE_NEXUS"
                echo EXISTE_NEXUS
                echo "*****"
                script {
                    echo "***** VALIDA EXPRESION 3 VALORES Y CUARTO OPCIONAL y -RELEASE opcional"
                    if ( VERSION ==~ /^([0-9]+.[0-9]+(.[0-9]+)?(.[0-9]+)?)([-.]RELEASE)?$/ && EXISTE_NEXUS == "" ){
                        echo "El entregable ${env.ARTFACID_NEXUS} tiene una version valida ${VERSION} y la misma no existe disponible en Nexus"
                    }else{
                        echo "El entregable ${env.ARTFACID_NEXUS} NO tiene una version valida: ${VERSION}, o la misma ya está disponible en Nexus " + EXISTE_NEXUS
                        currentBuild.getRawBuild().getExecutor().interrupt(Result.ABORTED)
                        sleep(2)
                    }
                }                        
            }
        }//C_validVersion

        stage('Setup Flyway Conf') {
            environment {
                SERVICE_CREDS= credentials"${SERVICE_CREDS}"
            }

            steps{
                withCredentials([string(credentialsId: "${SERVICE_CREDS}", variable: 'PASS')]) { /* CREAR PREVIAMENTE EL TIPO SECRET TEXT */
                    /** URL de Database ambiente development(DEV) release-*(TEST) **/
                    sh 'sed -i "1d" flyway-local.conf'
                    sh '''sed -i "1i\fflyway.url='''+URL_DB+''' " flyway-local.conf'''

                    sh 'sed -i "2d" flyway-local.conf'
                    sh '''sed -i "2i\fflyway.user='''+FLYWAY_USER+''' " flyway-local.conf'''
                    /* Se debe crear previamente el secret text en Jenkins con la clave de la BD*/
                    sh 'sed -i "3d" flyway-local.conf'
                    sh 'sed -i 3i\fflyway.password=$PASS flyway-local.conf'

                    sh 'sed -i "4d" flyway-local.conf'
                    sh '''sed -i '$a flyway.encoding=UTF-8' flyway-local.conf'''

                    sh 'cat flyway-local.conf'
                }
            }
        }//C_Flyway

        stage('Run Migrations against DB') {
            steps{
                script{
                    sh 'echo Run Migrations on ${PATH_DEPLOY}'
                    sh '/opt/maven/bin/mvn -X -Dflyway.configFiles=flyway-local.conf flyway:migrate -e'
                }
            }
        }//C_Migrations

        stage('Retain'){
            when{
                branch 'release-*'
            }

            steps{   
                echo "muestra el diferencial entre la rama que se ejecuta y master"
                sh "git diff --name-only -z origin/${BRANCH_NAME} origin/master ./src/main/resources/db/migration/"
                
                echo "Extrae el diferencial entre la rama que se ejecuta y master"
                sh "git diff --name-only -z origin/${BRANCH_NAME} origin/master ./src/main/resources/db/migration | xargs -0 git archive -o ${VERSION}.tar HEAD --"
                sh "tar -tvf ${VERSION}.tar"

            	echo "Retaining artifact in Nexus..."
                withCredentials([usernamePassword(credentialsId: 'Release-NEXUS-TOOLTEST', passwordVariable: 'PASSWORD_NEXUS', usernameVariable: 'USERNAME_NEXUS')]) {
                    sh "curl -v -u ${USERNAME_NEXUS}:${PASSWORD_NEXUS} -X POST '${URL_NEXUS}/service/rest/v1/components?repository=${REPOSITORY_NEXUS}' -H 'accept: application/json' -H 'Content-Type: multipart/form-data' -F 'maven2.groupId=${GROUPID_NEXUS}' -F 'maven2.artifactId=${ARTFACID_NEXUS}' -F 'maven2.version=${VERSION}' -F 'maven2.generate-pom=true' -F 'maven2.packaging=tar' -F 'maven2.asset1.extension=tar' -F 'maven2.asset1=@./${VERSION}.tar' "
                }
            }
        }//C_retain
    }//C_stages

    post{
        success {
			script {
				echo 'Success'
					emailext (
						mimeType: 'text/html',
						from: "${EMAIL}",
						body: """
					   <h2 style="text-align:center; border: 2px solid black; border-radius: 25px; background:#33cc33; color: white;">Despliegue Satisfactorio</h2>
						<p> Se completo de forma <b>SATISFACTORIA</b> el despliegue de ${PROJECT_NAME} en el ambiente de  ${PATH_DEPLOY}</p>  
						</br>
						<p>Puede referise a los siguientes enlaces para mayor detalle:</p>
						<ul>
							<li><b>Job ejecutado en Jenkins y detalles de ejecucion: </b> <a href=${env.BUILD_URL}> Jenkins </a> </li>
							<li><b>Ejecutado por: </b> ${causeUserID.userName}</li>
						<ul>
						""",
						to: "${SENDTOEMAIL}",
						replyTo: "${SENDTOEMAIL}",
						subject: "${DEFAULT_SUBJECT} OK"
					)
			}//c_script
		}//c_succes
			
		failure {
			script {
				echo 'Failure'
					emailext (
						mimeType: 'text/html',
						from: "${EMAIL}",
						body: """
							<h2 style="text-align:center; border: 2px solid black; border-radius: 25px; background:#FDFD96; color: black;">Build Fallido</h2>
                            <p> Se completo de forma <b>INSATISFACTORIA</b> el push de la imagen de Docker de ${PROJECT_NAME} en el ambiente de  ${PATH_DEPLOY}</p>  
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
						subject: "ERR - ${DEFAULT_SUBJECT}"
					)
			}
		}//c_failure
        
        cleanup {
            echo 'Drop Files on local Repo'
            deleteDir() 
        }
    }//c_post
}//C_pipeline
