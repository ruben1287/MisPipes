pipeline{
    agent {
        label 'servidor'
    }
    environment{
        //--   AWS
		//AWS_CREDENTIAL = "JEN-AWS-SM-PRE"
		AWS_CREDENTIAL = "CREDENCIAL" 
		SERVICE_CREDS = credentials("$AWS_CREDENTIAL")                  
		AWS_REGION = "us-east-1"
		SECRET_NAME = "NOMBRE_SECRETO"
        SECRET_STRING = '{"NAME1":"VALUE1","NAME2":"VALUE2"}'
		DESCRIPTION= "Secretos AWS"

        //-- NOTIFICACIONES
        PROJECT_NAME = 'SECRET_INITIAL_LOAD'
        SERVICE_DIRECTORY="secret_initial_load"
        SENDTOEMAIL="DESTINATARIO"
		DEFAULT_SUBJECT="SECRET_INITIAL_LOAD"                   	 // SUBJECT DEL MAIL A ENVIAR
		ENV_NAME="AMBIENTE"                                         // AMBIENTE
        EMAIL="QUIEN ENNVIA EL MAIL"                               	// CASILLA DE ENVIO DE MAIL
    }
    stages{
		stage ('Create Secret')
		{
            steps{
				echo '-------------'
				echo 'Creacion de secreto'
				echo '-------------'
				dir(SERVICE_DIRECTORY){
					//DEBE EJECUTARSE TODO EN LA MISMA SESION sh
                    sh '''
					export HOME=${WORKSPACE}
                    export AWS_ACCESS_KEY_ID=$SERVICE_CREDS_USR
                    export AWS_SECRET_ACCESS_KEY=$SERVICE_CREDS_PSW                    
                    whoami                    
                    
                    aws sts get-caller-identity
                    
                    aws secretsmanager create-secret --name $SECRET_NAME --description $DESCRIPTION --secret-string $SECRET_STRING
					
					unset AWS_ACCESS_KEY_ID
                    unset AWS_SECRET_ACCESS_KEY
                    '''					
				}
				echo '-------------'
                echo 'Fin de creacion'
				echo '-------------'               
            }
        }//C_secret
    }
	post {
		success {
			script {
				echo 'Success'
			}//c_script
		}//c_succes
			
		failure {
			script {
				echo 'Failure'
			}
		}//c_failure
			
		cleanup {
			echo 'Drop Files on local Repo'
			deleteDir() 
		}
	}//c_post
}